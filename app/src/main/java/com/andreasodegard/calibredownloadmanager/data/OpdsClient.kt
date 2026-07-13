package com.andreasodegard.calibredownloadmanager.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import java.io.StringWriter
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * OPDS client — fetches Atom feeds, parses entries, and constructs
 * Calibre download URLs directly (see the PWA docs write-up):
 *   content server : {origin}/get/{FMT}/{id}[/{library}]
 *   calibre-web    : {origin}/opds/download/{id}/{fmt}/
 */
object OpdsClient {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val REL_ACQ = "http://opds-spec.org/acquisition"

    suspend fun fetchFeed(url: String): Feed = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Accept", "application/atom+xml,application/xml,text/xml")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val text = resp.body?.string() ?: throw IOException("Empty response")
            parseFeed(text, url)
        }
    }

    fun parseFeed(xml: String, baseUrl: String): Feed {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }        // unsupported on Android
            runCatching { isExpandEntityReferences = false }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = try {
            dbf.newDocumentBuilder().parse(xml.byteInputStream())
        } catch (_: Exception) {
            throw IOException("Not a valid OPDS/Atom feed")
        }
        val feedEl = doc.documentElement ?: throw IOException("Not a valid OPDS/Atom feed")

        val feedLinks = childElements(feedEl, "link")
        val next = feedLinks.firstOrNull { it.getAttribute("rel") == "next" }

        val entries = childElements(feedEl, "entry").map { en ->
            val links = childElements(en, "link").map {
                OpdsLink(
                    rel = it.getAttribute("rel"),
                    type = it.getAttribute("type"),
                    href = resolveUrl(baseUrl, it.getAttribute("href")),
                )
            }
            val acq = links.filter { it.rel.startsWith(REL_ACQ) }
            val nav = links.firstOrNull {
                it.type.contains("profile=opds-catalog") && !it.rel.startsWith(REL_ACQ)
            }
            val thumb = links.firstOrNull { Regex("image/thumbnail|/thumb/").containsMatchIn(it.rel + it.href) }
                ?: links.firstOrNull { it.rel.contains("image") }
            val cover = links.firstOrNull {
                (it.rel.contains("image") && !it.rel.contains("thumbnail")) ||
                    Regex("/get/cover/|/opds/cover/").containsMatchIn(it.href)
            } ?: thumb
            val contentEl = childElements(en, "content").firstOrNull()
            OpdsEntry(
                title = childElements(en, "title").firstOrNull()?.textContent?.trim().orEmpty()
                    .ifEmpty { "(untitled)" },
                authors = childElements(en, "author").mapNotNull { a ->
                    childElements(a, "name").firstOrNull()?.textContent?.trim()
                }.filter { it.isNotEmpty() },
                contentText = contentEl?.textContent.orEmpty(),
                contentHtml = contentHtmlOf(contentEl),
                links = links,
                navHref = if (acq.isEmpty() && nav != null) nav.href else null,
                thumbHref = thumb?.href,
                coverHref = cover?.href,
            )
        }
        return Feed(
            title = childElements(feedEl, "title").firstOrNull()?.textContent?.trim().orEmpty(),
            next = next?.let { resolveUrl(baseUrl, it.getAttribute("href")) },
            entries = entries,
        )
    }

    private fun childElements(el: Element, localName: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = el.firstChild
        while (n != null) {
            if (n is Element && (n.localName ?: n.nodeName) == localName) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun resolveUrl(base: String, href: String): String =
        try { URL(URL(base), href).toString() } catch (_: Exception) { href }

    /** Atom <content> can be plain text, escaped HTML (type="html") or real
     *  XHTML child nodes (type="xhtml"). Return it as an HTML string. */
    private fun contentHtmlOf(contentEl: Element?): String {
        if (contentEl == null) return ""
        val type = contentEl.getAttribute("type").lowercase().ifEmpty { "text" }
        return when {
            type == "xhtml" -> {
                val tf = TransformerFactory.newInstance().newTransformer().apply {
                    setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                }
                val sb = StringBuilder()
                var n: Node? = contentEl.firstChild
                while (n != null) {
                    val w = StringWriter()
                    runCatching { tf.transform(DOMSource(n), StreamResult(w)) }
                    sb.append(w)
                    n = n.nextSibling
                }
                sb.toString()
            }
            type.contains("html") -> contentEl.textContent.orEmpty() // escaped markup
            // Plain text (or HTML mislabeled as text, which some servers send).
            // AnnotatedString.fromHtml renders plain text unchanged, so pass through.
            else -> contentEl.textContent.orEmpty()
        }
    }

    /**
     * Scrape book ID, library ID, server type and available formats from an
     * entry's links and summary text. Audio formats hidden from OPDS still
     * appear in the "FORMATS: EPUB, M4B, …" line Calibre writes into the
     * entry summary.
     */
    fun analyzeBook(entry: OpdsEntry): BookInfo {
        var bookId: String? = null
        var libraryId: String? = null
        var server: String? = null
        val formats = LinkedHashSet<String>()

        val reGet = Regex("/get/([^/]+)/(\\d+)(?:/([^/?#]+))?")
        val reWebDl = Regex("/opds/download/(\\d+)/([^/]+)")
        val reWebCover = Regex("/opds/cover(?:_\\d+)?/(\\d+)")
        val reCoverThumb = Regex("^(cover|thumb)$", RegexOption.IGNORE_CASE)

        for (l in entry.links) {
            reGet.find(l.href)?.let { m ->
                server = "content"
                if (bookId == null) bookId = m.groupValues[2]
                if (m.groupValues[3].isNotEmpty()) libraryId = m.groupValues[3]
                if (!reCoverThumb.matches(m.groupValues[1])) formats.add(m.groupValues[1].uppercase())
            }
            reWebDl.find(l.href)?.let { m ->
                server = "calibre-web"
                if (bookId == null) bookId = m.groupValues[1]
                formats.add(m.groupValues[2].uppercase())
            }
            reWebCover.find(l.href)?.let { m ->
                if (server == null) server = "calibre-web"
                if (bookId == null) bookId = m.groupValues[1]
            }
        }

        Regex("formats?\\s*:\\s*([a-z0-9, ]+)", RegexOption.IGNORE_CASE)
            .find(entry.contentText)?.let { m ->
                m.groupValues[1].split(Regex("[,\\s]+"))
                    .forEach { if (it.isNotBlank()) formats.add(it.uppercase()) }
            }

        // Keep only formats we know how to route, in category order.
        val dl = buildList {
            for (cat in Category.entries)
                for (f in cat.formats) if (f in formats) add(DownloadOption(f, cat))
        }
        return BookInfo(bookId, libraryId, server, dl)
    }

    fun buildDownloadUrl(opdsUrl: String, info: BookInfo, fmt: String): String {
        val u = URL(opdsUrl)
        val origin = u.protocol + "://" + u.host +
            (if (u.port != -1 && u.port != u.defaultPort) ":${u.port}" else "")
        return if (info.server == "calibre-web")
            "$origin/opds/download/${info.bookId}/${fmt.lowercase()}/"
        else
            "$origin/get/${fmt.uppercase()}/${info.bookId}" +
                (info.libraryId?.let { "/" + Uri.encode(it) } ?: "")
    }
}

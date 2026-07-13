package com.andreasodegard.calibredownloadmanager.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/** Persisted app settings: OPDS root URL + one SAF tree URI per format family. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var opdsUrl: String
        get() = (prefs.getString("opdsUrl", "") ?: "").trimEnd('/')
        set(v) = prefs.edit { putString("opdsUrl", v.trimEnd('/')) }

    fun getFolder(cat: Category): Uri? =
        prefs.getString("dir-${cat.key}", null)?.let(Uri::parse)

    fun setFolder(cat: Category, uri: Uri?) = prefs.edit {
        if (uri == null) remove("dir-${cat.key}") else putString("dir-${cat.key}", uri.toString())
    }
}

/** SAF folder access: list media files (with sidecar detection) and delete. */
object LibraryRepo {

    private data class Row(val docId: String, val name: String, val size: Long, val mtime: Long, val mime: String)

    fun list(context: Context, treeUri: Uri, cat: Category): List<MediaFile> {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val rows = ArrayList<Row>()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                rows.add(Row(c.getString(0), c.getString(1) ?: continue, c.getLong(2), c.getLong(3), c.getString(4) ?: ""))
            }
        } ?: throw IOException("Cannot read folder — pick it again in Settings")

        val files = rows.filter { it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
        // Sidecars may be "Book.m4b.json" or "Book.json" — both belong to the media file.
        val jsonByLower = files.filter { it.name.lowercase().endsWith(".json") }
            .associateBy { it.name.lowercase() }
        val exts = cat.formats.map { it.lowercase() }

        return files.mapNotNull { r ->
            val lower = r.name.lowercase()
            if (lower.endsWith(".json")) return@mapNotNull null // never shown
            val ext = lower.substringAfterLast('.', "")
            if (ext !in exts) return@mapNotNull null
            val base = lower.substringBeforeLast('.')
            val sidecars = listOfNotNull(jsonByLower["$lower.json"], jsonByLower["$base.json"])
            MediaFile(
                name = r.name, ext = ext, size = r.size, mtime = r.mtime,
                hasSidecar = sidecars.isNotEmpty(),
                documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, r.docId),
                sidecarUris = sidecars.map { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.docId) },
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** Deletes the media file and any sidecar .json files alongside it. */
    fun delete(context: Context, m: MediaFile) {
        if (!DocumentsContract.deleteDocument(context.contentResolver, m.documentUri))
            throw IOException("Could not delete “${m.name}”")
        for (s in m.sidecarUris) runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, s)
        }
    }

    fun folderName(context: Context, treeUri: Uri): String =
        DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?: "folder"

    fun hasPermission(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
}

/** Streams a download straight into a SAF folder, overwriting an existing file. */
object Downloader {

    suspend fun download(
        context: Context,
        url: String,
        treeUri: Uri,
        filename: String,
        onProgress: (got: Long, total: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // Overwrite semantics: remove any existing file with the same name first,
        // otherwise SAF would create "name (1)".
        findChildByName(resolver, treeUri, filename)?.let {
            runCatching { DocumentsContract.deleteDocument(resolver, it) }
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val fileUri = DocumentsContract.createDocument(
            resolver, parentUri, "application/octet-stream", filename
        ) ?: throw IOException("Could not create file in folder")

        var got = 0L
        try {
            val req = Request.Builder().url(url).build()
            OpdsClient.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
                val body = resp.body ?: throw IOException("Empty response body")
                val total = body.contentLength()
                (resolver.openOutputStream(fileUri, "w")
                    ?: throw IOException("Could not open file for writing")).use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        onProgress(got, total)
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, fileUri) }
            throw e
        }
        got
    }

    private fun findChildByName(resolver: ContentResolver, treeUri: Uri, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if ((c.getString(1) ?: "").equals(name, ignoreCase = true))
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
            }
        }
        return null
    }
}

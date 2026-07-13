package com.andreasodegard.calibredownloadmanager.data

import android.net.Uri
import java.util.Locale

/** Format families: each one downloads into its own device folder. */
enum class Category(val key: String, val label: String, val formats: List<String>) {
    AUDIO("audio", "Audiobooks", listOf("MP3", "M4B", "M4A")),
    BOOK("book", "Books", listOf("EPUB", "MOBI", "AZW3", "AZW", "FB2")),
    COMIC("comic", "Comics", listOf("PDF", "CBZ", "CBR", "CBT", "CB7"));
}

data class OpdsLink(val rel: String, val type: String, val href: String)

data class OpdsEntry(
    val title: String,
    val authors: List<String>,
    val contentText: String,
    val contentHtml: String,
    val links: List<OpdsLink>,
    val navHref: String?,
    val thumbHref: String?,
    val coverHref: String?,
)

data class Feed(val title: String, val next: String?, val entries: List<OpdsEntry>)

data class DownloadOption(val fmt: String, val cat: Category)

/** What analyzeBook() extracts from an acquisition entry. */
data class BookInfo(
    val bookId: String?,
    val libraryId: String?,
    val server: String?, // "content" | "calibre-web" | null
    val dl: List<DownloadOption>,
)

data class BookRow(val entry: OpdsEntry, val info: BookInfo)

/** A media file found in one of the device folders. */
data class MediaFile(
    val name: String,
    val ext: String,
    val size: Long,
    val mtime: Long,
    val hasSidecar: Boolean,
    val documentUri: Uri,
    val sidecarUris: List<Uri>,
)

fun sanitizeFilename(s: String): String =
    s.replace(Regex("[\\\\/:*?\"<>|]"), "-").replace(Regex("\\s+"), " ").trim().take(180)

fun formatSize(bytes: Long?): String {
    if (bytes == null) return ""
    var b = bytes.toDouble()
    val units = listOf("B", "KB", "MB", "GB")
    var i = 0
    while (b >= 1024 && i < units.size - 1) { b /= 1024; i++ }
    return (if (i == 0) b.toInt().toString() else String.format(Locale.ROOT, "%.1f", b)) + " " + units[i]
}

package com.andreasodegard.calibredownloadmanager

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andreasodegard.calibredownloadmanager.data.BookInfo
import com.andreasodegard.calibredownloadmanager.data.BookRow
import com.andreasodegard.calibredownloadmanager.data.Category
import com.andreasodegard.calibredownloadmanager.data.Downloader
import com.andreasodegard.calibredownloadmanager.data.LibraryRepo
import com.andreasodegard.calibredownloadmanager.data.MediaFile
import com.andreasodegard.calibredownloadmanager.data.OpdsClient
import com.andreasodegard.calibredownloadmanager.data.OpdsEntry
import com.andreasodegard.calibredownloadmanager.data.Settings
import com.andreasodegard.calibredownloadmanager.data.formatSize
import com.andreasodegard.calibredownloadmanager.data.sanitizeFilename
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StatusKind { OK, WARN, ERR }
data class StatusMsg(val text: String, val kind: StatusKind)

data class Crumb(val title: String, val url: String)

sealed interface LibSection {
    data object Loading : LibSection
    data class Error(val msg: String) : LibSection
    data class Files(val files: List<MediaFile>) : LibSection
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val settings = Settings(app)

    /* ---------------- toasts ---------------- */
    private val _toasts = Channel<String>(Channel.BUFFERED)
    val toasts = _toasts.receiveAsFlow()
    fun toast(msg: String) { _toasts.trySend(msg) }

    /* ---------------- settings ---------------- */
    var opdsUrl by mutableStateOf(settings.opdsUrl)
        private set
    var urlStatus by mutableStateOf<StatusMsg?>(null)
        private set

    val folders = mutableStateMapOf<Category, Uri?>()
    val folderNames = mutableStateMapOf<Category, String>()

    init {
        for (cat in Category.entries) {
            val u = settings.getFolder(cat)
            // Drop saved folders whose permission has been revoked.
            if (u != null && LibraryRepo.hasPermission(ctx, u)) {
                folders[cat] = u
                folderNames[cat] = LibraryRepo.folderName(ctx, u)
            } else {
                folders[cat] = null
            }
        }
    }

    fun saveUrl(v: String) {
        val trimmed = v.trim().trimEnd('/')
        if (trimmed.isNotEmpty() && !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            urlStatus = StatusMsg("URL must start with http:// or https://", StatusKind.ERR)
            return
        }
        settings.opdsUrl = trimmed
        opdsUrl = trimmed
        urlStatus = StatusMsg(if (trimmed.isEmpty()) "Cleared." else "Saved.", StatusKind.OK)
        browseReset = true
    }

    fun testUrl(v: String) {
        val u = v.trim().trimEnd('/')
        if (u.isEmpty()) { urlStatus = StatusMsg("Enter a URL first.", StatusKind.WARN); return }
        urlStatus = StatusMsg("Testing…", StatusKind.WARN)
        viewModelScope.launch {
            urlStatus = try {
                val feed = OpdsClient.fetchFeed(u)
                StatusMsg("OK — connected to “${feed.title.ifEmpty { "(untitled feed)" }}”.", StatusKind.OK)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StatusMsg("Failed: ${e.message}", StatusKind.ERR)
            }
        }
    }

    fun setFolder(cat: Category, uri: Uri) {
        settings.setFolder(cat, uri)
        folders[cat] = uri
        folderNames[cat] = LibraryRepo.folderName(ctx, uri)
        toast("${cat.label} folder saved.")
        refreshLibrary()
    }

    fun forgetFolder(cat: Category) {
        settings.setFolder(cat, null)
        folders[cat] = null
        folderNames.remove(cat)
        refreshLibrary()
    }

    /* ---------------- device library ---------------- */
    val library = mutableStateMapOf<Category, LibSection>()
    private val haveFiles = mutableStateMapOf<Category, Set<String>>() // lower-cased names

    init {
        // Runs after all properties above are initialized.
        refreshLibrary()
    }

    fun refreshLibrary() {
        for (cat in Category.entries) {
            val uri = folders[cat]
            if (uri == null) {
                library.remove(cat)
                haveFiles[cat] = emptySet()
                continue
            }
            library[cat] = LibSection.Loading
            viewModelScope.launch {
                try {
                    val files = withContext(Dispatchers.IO) { LibraryRepo.list(ctx, uri, cat) }
                    library[cat] = LibSection.Files(files)
                    haveFiles[cat] = files.map { it.name.lowercase() }.toSet()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    library[cat] = LibSection.Error(e.message ?: "read error")
                    haveFiles[cat] = emptySet()
                }
            }
        }
    }

    fun hasLocal(cat: Category, entryTitle: String, fmt: String): Boolean =
        haveFiles[cat]?.contains((sanitizeFilename(entryTitle) + "." + fmt.lowercase()).lowercase()) == true

    fun deleteMedia(m: MediaFile) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { LibraryRepo.delete(ctx, m) }
                toast("Deleted.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Delete failed: ${e.message}")
            }
            refreshLibrary()
        }
    }

    /* ---------------- OPDS browsing ---------------- */
    val crumbs = mutableStateListOf<Crumb>()
    val entries = mutableStateListOf<BookRow>()
    var nextUrl by mutableStateOf<String?>(null)
        private set
    var browseLoading by mutableStateOf(false)
        private set
    var browseError by mutableStateOf<String?>(null)
        private set
    private var browseReset = true
    private var loadJob: Job? = null

    fun initBrowse() {
        if (opdsUrl.isEmpty()) return
        if (browseReset || crumbs.isEmpty()) {
            browseReset = false
            crumbs.clear()
            crumbs.add(Crumb("Catalog", opdsUrl))
            loadCurrent()
        }
    }

    fun navigateTo(title: String, url: String) {
        crumbs.add(Crumb(title, url))
        loadCurrent()
    }

    fun crumbTo(index: Int) {
        while (crumbs.size > index + 1) crumbs.removeAt(crumbs.size - 1)
        loadCurrent()
    }

    fun loadMore() { nextUrl?.let { loadCurrent(append = it) } }

    fun search(q: String) {
        if (q.isBlank() || opdsUrl.isEmpty()) return
        crumbs.clear()
        crumbs.add(Crumb("Catalog", opdsUrl))
        crumbs.add(Crumb("Search: $q", "$opdsUrl/search/${Uri.encode(q.trim())}"))
        loadCurrent()
    }

    private fun loadCurrent(append: String? = null) {
        val url = append ?: crumbs.lastOrNull()?.url ?: return
        loadJob?.cancel()
        browseError = null
        browseLoading = true
        if (append == null) { entries.clear(); nextUrl = null }
        loadJob = viewModelScope.launch {
            try {
                val feed = OpdsClient.fetchFeed(url)
                nextUrl = feed.next
                entries.addAll(feed.entries.map { BookRow(it, OpdsClient.analyzeBook(it)) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                browseError = "Failed to load feed: ${e.message}"
            }
            browseLoading = false
        }
    }

    /* ---------------- downloads ---------------- */
    /** key → progress fraction 0..1, or -1 when total size is unknown. */
    val downloads = mutableStateMapOf<String, Float>()

    fun dlKey(entry: OpdsEntry, fmt: String) = entry.title + "|" + fmt

    fun download(entry: OpdsEntry, info: BookInfo, fmt: String, cat: Category) {
        val dirUri = folders[cat]
        if (dirUri == null) { toast("Pick a ${cat.label} folder in Settings first."); return }
        if (info.bookId == null) { toast("No book ID found in entry links."); return }
        val key = dlKey(entry, fmt)
        if (downloads.containsKey(key)) return
        downloads[key] = -1f
        viewModelScope.launch {
            try {
                val url = OpdsClient.buildDownloadUrl(opdsUrl, info, fmt)
                val filename = sanitizeFilename(entry.title) + "." + fmt.lowercase()
                var lastPct = -1
                val got = Downloader.download(ctx, url, dirUri, filename) { g, total ->
                    if (total > 0) {
                        val pct = (g * 100 / total).toInt()
                        if (pct != lastPct) { lastPct = pct; downloads[key] = g.toFloat() / total }
                    }
                }
                haveFiles[cat] = (haveFiles[cat] ?: emptySet()) + filename.lowercase()
                toast("Downloaded “$filename” (${formatSize(got)}) → ${cat.label}.")
                refreshLibrary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Download failed: ${e.message}")
            } finally {
                downloads.remove(key)
            }
        }
    }
}

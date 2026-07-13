package com.andreasodegard.calibredownloadmanager.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.andreasodegard.calibredownloadmanager.AppViewModel
import com.andreasodegard.calibredownloadmanager.Crumb
import com.andreasodegard.calibredownloadmanager.LibSection
import com.andreasodegard.calibredownloadmanager.StatusKind
import com.andreasodegard.calibredownloadmanager.StatusMsg
import com.andreasodegard.calibredownloadmanager.data.BookRow
import com.andreasodegard.calibredownloadmanager.data.Category
import com.andreasodegard.calibredownloadmanager.data.MediaFile
import com.andreasodegard.calibredownloadmanager.data.formatSize
import java.text.DateFormat
import java.util.Date

/* =====================================================================
 * Main screen: tabs Library / Browse / Settings
 * ===================================================================== */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.toasts.collect { snackbar.showSnackbar(it) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Calibre Download Manager") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Library", "Browse", "Settings").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            when (tab) {
                0 -> LibraryTab(vm)
                1 -> BrowseTab(vm)
                2 -> SettingsTab(vm)
            }
        }
    }
    LaunchedEffect(tab) {
        if (tab == 0) vm.refreshLibrary()
        if (tab == 1) vm.initBrowse()
    }
}

@Composable
private fun MutedText(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun StatusText(status: StatusMsg?) {
    if (status == null) return
    Text(
        status.text,
        style = MaterialTheme.typography.bodySmall,
        color = when (status.kind) {
            StatusKind.OK -> MaterialTheme.colorScheme.primary
            StatusKind.WARN -> MaterialTheme.colorScheme.tertiary
            StatusKind.ERR -> MaterialTheme.colorScheme.error
        },
        modifier = Modifier.padding(top = 6.dp),
    )
}

/* =====================================================================
 * DEVICE LIBRARY — one section per configured folder; sidecar .json
 * files are hidden and deleted with their media file.
 * ===================================================================== */
@Composable
private fun LibraryTab(vm: AppViewModel) {
    val anyDir = Category.entries.any { vm.folders[it] != null }
    if (!anyDir) {
        Card(Modifier.padding(16.dp).fillMaxWidth()) {
            MutedText(
                "No download folders selected yet. Pick them in Settings.",
                Modifier.padding(14.dp),
            )
        }
        return
    }
    var pendingDelete by remember { mutableStateOf<MediaFile?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { vm.refreshLibrary() }) { Text("Refresh") }
            }
        }
        for (cat in Category.entries) {
            if (vm.folders[cat] == null) continue
            val section = vm.library[cat]
            val count = (section as? LibSection.Files)?.files?.size
            item(key = "head-${cat.key}") {
                Column(Modifier.padding(top = 18.dp, bottom = 6.dp)) {
                    Text(
                        "${cat.label}${count?.let { " ($it)" } ?: ""}",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    MutedText("Folder: ${vm.folderNames[cat] ?: ""}")
                }
            }
            when (section) {
                null, LibSection.Loading -> item(key = "load-${cat.key}") {
                    CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp))
                }
                is LibSection.Error -> item(key = "err-${cat.key}") {
                    Text(section.msg, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                is LibSection.Files -> {
                    if (section.files.isEmpty()) item(key = "empty-${cat.key}") {
                        MutedText("No ${cat.formats.joinToString(" / ") { it.lowercase() }} files in this folder yet.")
                    }
                    items(section.files, key = { "${cat.key}-${it.name}" }) { m ->
                        MediaFileRow(m, onDelete = { pendingDelete = m })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete file?") },
            text = { Text("Delete “${m.name}”${if (m.hasSidecar) " and its sync data file" else ""}?") },
            confirmButton = {
                TextButton(onClick = { vm.deleteMedia(m); pendingDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MediaFileRow(m: MediaFile, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp)) {
                BadgeChip(m.ext.uppercase())
                if (m.hasSidecar) BadgeChip("sync data", ok = true)
                MutedText("${formatSize(m.size)} · ${DateFormat.getDateInstance().format(Date(m.mtime))}")
            }
        }
        TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun BadgeChip(
    text: String,
    ok: Boolean = false,
    accent: Boolean = false,
    big: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val color = when {
        ok -> MaterialTheme.colorScheme.primary
        accent -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    var mod = Modifier
        .border(1.dp, color, RoundedCornerShape(6.dp))
    if (onClick != null) mod = mod.clickable(onClick = onClick)
    Box(mod.padding(horizontal = if (big) 16.dp else 10.dp, vertical = if (big) 10.dp else 5.dp)) {
        Text(text, color = color,
            style = if (big) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge)
    }
}

/* =====================================================================
 * BROWSE — OPDS catalog navigation, search, downloads
 * ===================================================================== */
@Composable
private fun BrowseTab(vm: AppViewModel) {
    if (vm.opdsUrl.isEmpty()) {
        Card(Modifier.padding(16.dp).fillMaxWidth()) {
            MutedText("No Calibre OPDS URL configured yet. Set one in Settings.",
                Modifier.padding(14.dp))
        }
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<BookRow?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search library…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
            )
            Button(onClick = { vm.search(query) }) { Text("Search") }
        }

        // breadcrumbs
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            vm.crumbs.forEachIndexed { i, c: Crumb ->
                if (i > 0) MutedText(" › ")
                Text(
                    c.title,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { vm.crumbTo(i) },
                )
            }
        }

        vm.browseError?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp))
        }

        LazyColumn(Modifier.weight(1f)) {
            items(vm.entries) { row ->
                val navHref = row.entry.navHref
                if (navHref != null) NavEntryRow(row) { vm.navigateTo(row.entry.title, navHref) }
                else BookEntryRow(vm, row, onOpen = { selected = row })
                HorizontalDivider()
            }
            if (vm.browseLoading) item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
            if (!vm.browseLoading && vm.entries.isEmpty() && vm.browseError == null) item {
                MutedText("Empty feed.", Modifier.padding(vertical = 16.dp))
            }
            if (vm.nextUrl != null && !vm.browseLoading) item {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = { vm.loadMore() }) { Text("Load more") }
                }
            }
        }
    }

    selected?.let { row -> BookDialog(vm, row) { selected = null } }
}

@Composable
private fun NavEntryRow(row: BookRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("📁")
        Text(row.entry.title, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        MutedText("›")
    }
}

@Composable
private fun BookEntryRow(vm: AppViewModel, row: BookRow, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (row.entry.thumbHref != null) {
            AsyncImage(
                model = row.entry.thumbHref,
                contentDescription = null,
                modifier = Modifier.size(width = 52.dp, height = 74.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            )
        } else {
            Spacer(Modifier.size(width = 52.dp, height = 74.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(row.entry.title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.entry.authors.isNotEmpty())
                MutedText(row.entry.authors.joinToString(", "), Modifier.padding(top = 2.dp))
            FormatBadges(vm, row, big = false)
        }
    }
}

/** Download / have badges for a book, plus progress bars for running downloads. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatBadges(vm: AppViewModel, row: BookRow, big: Boolean) {
    val info = row.info
    if (info.dl.isEmpty()) { MutedText("no supported formats", Modifier.padding(top = 4.dp)); return }
    if (info.bookId == null) { MutedText("no book ID found in entry links", Modifier.padding(top = 4.dp)); return }
    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            for ((fmt, cat) in info.dl) {
                val have = vm.hasLocal(cat, row.entry.title, fmt)
                val downloading = vm.downloads.containsKey(vm.dlKey(row.entry, fmt))
                when {
                    have -> BadgeChip("$fmt ✓", ok = true, big = big)
                    downloading -> BadgeChip("… $fmt", accent = true, big = big)
                    else -> BadgeChip("⬇ $fmt", accent = true, big = big) {
                        vm.download(row.entry, info, fmt, cat)
                    }
                }
            }
        }
        for ((fmt, _) in info.dl) {
            val p = vm.downloads[vm.dlKey(row.entry, fmt)] ?: continue
            if (p >= 0f) LinearProgressIndicator(
                progress = { p },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp),
            )
            else LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp),
            )
        }
    }
}

/* ---------------- book detail dialog ---------------- */
@Composable
private fun BookDialog(vm: AppViewModel, row: BookRow, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val src = row.entry.coverHref ?: row.entry.thumbHref
                    if (src != null) AsyncImage(
                        model = src,
                        contentDescription = null,
                        modifier = Modifier.size(width = 100.dp, height = 142.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(row.entry.title, style = MaterialTheme.typography.titleMedium)
                        if (row.entry.authors.isNotEmpty())
                            MutedText(row.entry.authors.joinToString(", "), Modifier.padding(top = 4.dp))
                    }
                }
                FormatBadges(vm, row, big = true)
                if (row.entry.contentHtml.isNotBlank()) {
                    Text(
                        AnnotatedString.fromHtml(row.entry.contentHtml),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/* =====================================================================
 * SETTINGS — OPDS URL + one SAF folder per format family
 * ===================================================================== */
@Composable
private fun SettingsTab(vm: AppViewModel) {
    val context = LocalContext.current
    var url by rememberSaveable(vm.opdsUrl) { mutableStateOf(vm.opdsUrl) }
    var pickCat by remember { mutableStateOf<Category?>(null) }
    var pendingForget by remember { mutableStateOf<Category?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val cat = pickCat
        pickCat = null
        if (uri != null && cat != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.setFolder(cat, uri)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Calibre OPDS server", style = MaterialTheme.typography.titleMedium)
                MutedText(
                    "OPDS root URL (no authentication), e.g. http://192.168.1.10:8080/opds",
                    Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("http://ip:port/opds") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done,
                    ),
                )
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { vm.saveUrl(url) }) { Text("Save") }
                    OutlinedButton(onClick = { vm.testUrl(url) }) { Text("Test") }
                }
                StatusText(vm.urlStatus)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Download folders on this device", style = MaterialTheme.typography.titleMedium)
                MutedText(
                    "Each format family is saved to — and listed from — its own folder. " +
                        "Sidecar .json files are hidden from the library view.",
                    Modifier.padding(top = 4.dp),
                )
                for (cat in Category.entries) {
                    Text(
                        "${cat.label} folder (${cat.formats.joinToString(", ")})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickCat = cat; picker.launch(null) }) { Text("Choose folder…") }
                        OutlinedButton(
                            onClick = { pendingForget = cat },
                            enabled = vm.folders[cat] != null,
                        ) { Text("Forget") }
                    }
                    val name = vm.folderNames[cat]
                    StatusText(
                        if (vm.folders[cat] != null && name != null)
                            StatusMsg("Using folder “$name”.", StatusKind.OK)
                        else StatusMsg("No folder selected.", StatusKind.WARN)
                    )
                }
            }
        }
    }

    pendingForget?.let { cat ->
        AlertDialog(
            onDismissRequest = { pendingForget = null },
            title = { Text("Forget folder?") },
            text = {
                Text("Stop using folder “${vm.folderNames[cat] ?: ""}” for ${cat.label}? " +
                    "No files are deleted.")
            },
            confirmButton = {
                TextButton(onClick = { vm.forgetFolder(cat); pendingForget = null }) {
                    Text("Forget", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingForget = null }) { Text("Cancel") } },
        )
    }
}

package com.codepress.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.agent.FsEntry
import com.codepress.app.agent.SearchHit
import com.codepress.app.data.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private class NameReq(val title: String, val initial: String, val hint: String, val onOk: (String) -> Unit)

private fun parentOf(dir: String) = dir.substringBeforeLast('/', "")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(vm: ChatViewModel, onBack: () -> Unit, onOpenEditor: () -> Unit) {
    val rev by vm.revision.collectAsStateWithLifecycle()
    val status by vm.fileStatus.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    val focus by vm.filesFocus.collectAsStateWithLifecycle()
    var dir by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var searching by remember { mutableStateOf(false) }
    var plusMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var nameReq by remember { mutableStateOf<NameReq?>(null) }
    var deleteReq by remember { mutableStateOf<List<String>?>(null) }
    var destReq by remember { mutableStateOf<Pair<Boolean, List<String>>?>(null) }
    var extractReq by remember { mutableStateOf<String?>(null) }
    var zipPreview by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<String?>(null) }
    val download = rememberFileDownloader(vm)

    var pendingSel by remember { mutableStateOf<List<String>>(emptyList()) }
    val saveSelection = rememberLauncherForActivityResult(CreateDocWithType()) { uri -> if (uri != null) vm.exportPaths(pendingSel, uri) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.importFolder(uri) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importFilesInto(uris, dir) }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFilesInto(listOf(uri), dir) }
    val saveAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportZip(uri) }

    LaunchedEffect(focus) {
        focus?.let { dir = parentOf(it); highlight = it; vm.consumeFilesFocus() }
    }
    val entries = remember(rev, dir) { runCatching { vm.fileOps.list(dir) }.getOrNull() }
    LaunchedEffect(entries) { if (entries == null && dir.isNotEmpty()) dir = parentOf(dir) }
    val shown = entries ?: emptyList()
    val types = remember(rev) { runCatching { vm.fileOps.detectProject() }.getOrDefault(emptyList()) }

    BackHandler(enabled = selected.isNotEmpty() || searching || dir.isNotEmpty()) {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            searching -> searching = false
            else -> dir = parentOf(dir)
        }
    }

    fun openEntry(e: FsEntry) {
        when {
            e.isDir -> dir = e.path
            isZip(e.path) -> extractReq = e.path
            else -> { vm.openInEditor(e.path); onOpenEditor() }
        }
    }

    fun downloadSelection(paths: List<String>) {
        if (paths.size == 1 && !runCatching { vm.workspace.resolve(paths[0]).isDirectory }.getOrDefault(false)) download(paths[0])
        else if (paths.size == 1) download(paths[0] + "/")
        else { pendingSel = paths; saveSelection.launch("selection.zip" to "application/zip") }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    navigationIcon = { IconAction(Icons.Rounded.Close, "Cancel selection", { selected = emptySet() }) },
                    title = { Text("${selected.size} selected") },
                    actions = {
                        val list = selected.toList()
                        IconAction(Icons.Rounded.ContentCopy, "Copy to…", { destReq = false to list })
                        IconAction(Icons.Rounded.ContentCut, "Move to…", { destReq = true to list })
                        IconAction(Icons.Rounded.Download, "Download", { downloadSelection(list) })
                        IconAction(Icons.Rounded.Share, "Share", { vm.sharePaths(list) })
                        IconAction(Icons.Rounded.Delete, "Delete", { deleteReq = list }, tint = MaterialTheme.colorScheme.error)
                    },
                )
            } else {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = { IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { if (dir.isNotEmpty()) dir = parentOf(dir) else onBack() }) },
                    title = {
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            Text(project?.name ?: "Files", Modifier.clickable { dir = "" }, maxLines = 1, color = if (dir.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            var acc = ""
                            dir.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                                acc = if (acc.isEmpty()) seg else "$acc/$seg"
                                val target = acc
                                Text("  /  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(seg, Modifier.clickable { dir = target }, maxLines = 1)
                            }
                        }
                    },
                    actions = {
                        IconAction(Icons.Rounded.Search, "Search project", { searching = !searching })
                        IconAction(Icons.Rounded.Add, "Add", { plusMenu = true })
                        DropdownMenu(expanded = plusMenu, onDismissRequest = { plusMenu = false }) {
                            DropdownMenuItem(text = { Text("New file") }, onClick = { plusMenu = false; nameReq = NameReq("New file", "", "name.ext") { n -> vm.fsCreateFile(dir, n, open = true); onOpenEditor() } })
                            DropdownMenuItem(text = { Text("New folder") }, onClick = { plusMenu = false; nameReq = NameReq("New folder", "", "folder name") { n -> vm.fsMkdir(dir, n) } })
                            DropdownMenuItem(text = { Text("Upload file") }, onClick = { plusMenu = false; pickFiles.launch(arrayOf("*/*")) })
                            DropdownMenuItem(text = { Text("Upload ZIP") }, onClick = { plusMenu = false; pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) })
                            DropdownMenuItem(text = { Text("Import folder") }, onClick = { plusMenu = false; pickFolder.launch(null) })
                        }
                        IconAction(Icons.Rounded.MoreVert, "More", { moreMenu = true })
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text("Download project as ZIP") }, enabled = shown.isNotEmpty() || dir.isNotEmpty(), onClick = { moreMenu = false; saveAll.launch("codepress-workspace.zip") })
                            DropdownMenuItem(text = { Text("Delete all files") }, onClick = { moreMenu = false; confirmClear = true })
                        }
                    },
                )
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (searching) {
                SearchPanel(vm, onOpenFile = { p, line -> vm.openInEditor(p, line); onOpenEditor() }, onOpenDir = { dir = it; searching = false })
            } else {
                val info = listOfNotNull(
                    types.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "$it project" },
                    status,
                ).joinToString("  ·  ")
                if (info.isNotEmpty()) Text(info, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (shown.isEmpty()) {
                    Text(
                        if (dir.isEmpty()) "This project has no files yet. Ask CodePress to create something, or tap + to add a file, folder or ZIP."
                        else "This folder is empty.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(shown, key = { it.path }) { e ->
                        FileRow(
                            e = e,
                            isSelected = e.path in selected,
                            selecting = selected.isNotEmpty(),
                            highlighted = e.path == highlight,
                            onClick = { if (selected.isNotEmpty()) selected = if (e.path in selected) selected - e.path else selected + e.path else openEntry(e) },
                            onLongClick = { selected = selected + e.path },
                            vm = vm,
                            onOpen = { openEntry(e) },
                            onPreview = { vm.previewFile(e.path) },
                            onDownload = { if (e.isDir) download(e.path + "/") else download(e.path) },
                            onRename = { nameReq = NameReq("Rename", e.name, "new name") { n -> vm.fsRename(e.path, n) } },
                            onDelete = { deleteReq = listOf(e.path) },
                            onCopy = { destReq = false to listOf(e.path) },
                            onMove = { destReq = true to listOf(e.path) },
                            onNewFile = { nameReq = NameReq("New file in ${e.name}", "", "name.ext") { n -> vm.fsCreateFile(e.path, n, open = true); onOpenEditor() } },
                            onNewFolder = { nameReq = NameReq("New folder in ${e.name}", "", "folder name") { n -> vm.fsMkdir(e.path, n) } },
                            onExtract = { extractReq = e.path },
                            onZipPreview = { zipPreview = e.path },
                            onImportProject = { vm.importZipAsProject(e.path) },
                            onShare = { vm.sharePaths(listOf(e.path)) },
                        )
                    }
                }
            }
        }
    }

    nameReq?.let { r -> NameDialog(r.title, r.initial, r.hint, { nameReq = null }) { n -> nameReq = null; r.onOk(n) } }

    deleteReq?.let { paths ->
        val count = remember(paths) { runCatching { paths.sumOf { vm.fileOps.countFiles(it) } }.getOrDefault(0) }
        val one = paths.singleOrNull()?.substringAfterLast('/')
        val isDir = paths.size == 1 && runCatching { vm.workspace.resolve(paths[0]).isDirectory }.getOrDefault(false)
        AlertDialog(
            onDismissRequest = { deleteReq = null },
            title = { Text("Delete?") },
            text = {
                Text(
                    when {
                        isDir -> "Delete \"$one\" and its $count file${if (count == 1) "" else "s"}?"
                        one != null -> "Delete \"$one\"?"
                        else -> "Delete ${paths.size} items ($count files)?"
                    } + "\nThis cannot be undone.",
                )
            },
            confirmButton = { TextButton(onClick = { deleteReq = null; selected = emptySet(); vm.fsDelete(paths) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteReq = null }) { Text("Cancel") } },
        )
    }

    destReq?.let { (move, paths) ->
        val dirs = remember(rev) { runCatching { vm.fileOps.allDirs() }.getOrDefault(listOf("")) }
        AlertDialog(
            onDismissRequest = { destReq = null },
            title = { Text(if (move) "Move to…" else "Copy to…") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(dirs, key = { it }) { d ->
                        Row(Modifier.fillMaxWidth().clickable {
                            destReq = null
                            selected = emptySet()
                            if (move) vm.fsMove(paths, d) else vm.fsCopy(paths, d)
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            FileIcon(d, true, 32.dp)
                            Text(if (d.isEmpty()) "Project root" else d, Modifier.padding(start = 8.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { destReq = null }) { Text("Cancel") } },
        )
    }

    extractReq?.let { zip ->
        val stem = zip.substringAfterLast('/').substringBeforeLast('.')
        val here = parentOf(zip)
        var intoNew by remember { mutableStateOf(true) }
        var deleteZip by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { extractReq = null },
            title = { Text("Extract ZIP") },
            text = {
                Column {
                    Text(zip.substringAfterLast('/'), fontFamily = FontFamily.Monospace)
                    Row(Modifier.clickable { intoNew = false }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = !intoNew, onClick = { intoNew = false }); Text("Current folder") }
                    Row(Modifier.clickable { intoNew = true }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = intoNew, onClick = { intoNew = true }); Text("New folder: $stem") }
                    Row(Modifier.clickable { deleteZip = !deleteZip }, verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = deleteZip, onCheckedChange = { deleteZip = it }); Text("Delete the ZIP afterwards") }
                    TextButton(onClick = { zipPreview = zip }) { Text("Preview contents") }
                }
            },
            confirmButton = { TextButton(onClick = { extractReq = null; vm.fsExtract(zip, if (intoNew) (if (here.isEmpty()) stem else "$here/$stem") else here, deleteZip) }) { Text("Extract") } },
            dismissButton = { TextButton(onClick = { extractReq = null }) { Text("Cancel") } },
        )
    }

    zipPreview?.let { zip ->
        val list by produceState<List<Pair<String, Long>>?>(null, zip) { value = withContext(Dispatchers.IO) { runCatching { vm.fileOps.zipEntries(zip) }.getOrNull() } }
        AlertDialog(
            onDismissRequest = { zipPreview = null },
            title = { Text(zip.substringAfterLast('/')) },
            text = {
                val l = list
                if (l == null) Text("Couldn't read this ZIP.") else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(l) { (n, size) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(n, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!n.endsWith("/")) Text(humanSize(size.coerceAtLeast(0)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { zipPreview = null }) { Text("Close") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all files of this project?") },
            text = { Text("Every file and folder the AI created or you imported is deleted. Download a ZIP first if you want to keep them.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; dir = ""; vm.clearWorkspace() }) { Text("Delete everything", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    e: FsEntry,
    isSelected: Boolean,
    selecting: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    vm: ChatViewModel,
    onOpen: () -> Unit,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onExtract: () -> Unit,
    onZipPreview: () -> Unit,
    onImportProject: () -> Unit,
    onShare: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val zip = !e.isDir && isZip(e.path)
    val bg = when {
        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        highlighted -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().background(bg).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 12.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            if (isSelected) Icon(Icons.Rounded.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary) else FileIcon(e.path, e.isDir)
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
            Text(e.name, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (e.isDir) "${e.items} item${if (e.items == 1) "" else "s"}" else humanSize(e.size),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!selecting) {
            if (!e.isDir && isPreviewable(e.path)) IconAction(Icons.Rounded.PlayCircle, "Run / preview", onPreview, tint = MaterialTheme.colorScheme.primary)
            IconAction(Icons.Rounded.MoreVert, "More actions for ${e.name}", { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                @Composable
                fun item(label: String, action: () -> Unit) = DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                if (zip) {
                    item("Extract / Unzip", onExtract)
                    item("Preview contents", onZipPreview)
                    item("Import as new project", onImportProject)
                } else {
                    item("Open", onOpen)
                    if (!e.isDir && isPreviewable(e.path)) item("Run / preview", onPreview)
                }
                if (e.isDir) { item("New file", onNewFile); item("New folder", onNewFolder) }
                item("Rename", onRename)
                item("Copy to…", onCopy)
                item("Move to…", onMove)
                item(if (e.isDir) "Download folder (ZIP)" else "Download", onDownload)
                item("Share", onShare)
                item("Copy path", { vm.copyText(e.path, "Path copied") })
                item("Delete", onDelete)
            }
        }
    }
}

@Composable
private fun SearchPanel(vm: ChatViewModel, onOpenFile: (String, Int) -> Unit, onOpenDir: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf(true) }
    val hits by produceState<List<SearchHit>>(emptyList(), query, content) {
        if (query.isBlank()) { value = emptyList(); return@produceState }
        delay(300)
        value = withContext(Dispatchers.IO) { runCatching { vm.fileOps.search(query, content) }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search files…") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Also search inside files", Modifier.weight(1f), fontSize = 13.sp)
            Switch(checked = content, onCheckedChange = { content = it })
        }
        HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn {
            items(hits, key = { "${it.path}:${it.line}:${it.text.hashCode()}" }) { h ->
                val isDir = h.text == "folder"
                Column(Modifier.fillMaxWidth().clickable { if (isDir) onOpenDir(h.path) else onOpenFile(h.path, h.line) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(h.path + if (h.line > 0) ":${h.line}" else "", fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(h.text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (query.isNotBlank() && hits.isEmpty()) Text("No results.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

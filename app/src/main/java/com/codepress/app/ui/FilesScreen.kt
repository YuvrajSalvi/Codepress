package com.codepress.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.data.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val rev by vm.revision.collectAsStateWithLifecycle()
    val status by vm.fileStatus.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    val files = remember(rev) { vm.workspace.listFiles() }
    val sizes = remember(rev, files) { files.associateWith { runCatching { vm.workspace.resolve(it).length() }.getOrDefault(0L) } }
    var open by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val download = rememberFileDownloader(vm)

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.importFolder(uri) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importFiles(uris) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportZip(uri) }

    BackHandler(enabled = open != null) { open = null }

    val current = open
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text(current?.substringAfterLast('/') ?: "Files", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = if (current != null) humanSize(sizes[current] ?: 0L) else (project?.name ?: "")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = { IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { if (current != null) open = null else onBack() }) },
                actions = {
                    if (current != null) {
                        if (isPreviewable(current)) IconAction(Icons.Rounded.PlayCircle, "Run / preview", { vm.previewFile(current) }, tint = MaterialTheme.colorScheme.primary)
                        IconAction(Icons.Rounded.Download, "Download", { download(current) })
                    }
                },
            )
        },
    ) { inner ->
        if (current != null) {
            val text by produceState("Loading…", current) {
                value = withContext(Dispatchers.IO) {
                    try {
                        val f = vm.workspace.resolve(current)
                        if (f.length() > 200_000) "(file is too large to preview here. Use the download button.)" else f.readText()
                    } catch (e: Exception) {
                        "Cannot read this file: ${e.message}"
                    }
                }
            }
            Text(
                text,
                modifier = Modifier
                    .padding(inner)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
            )
        } else {
            Column(Modifier.padding(inner).fillMaxSize()) {
                FlowRow(
                    Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Import folder") }
                    OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Import files") }
                    OutlinedButton(onClick = { saveZip.launch("codepress-workspace.zip") }, enabled = files.isNotEmpty()) { Text("Download ZIP") }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = files.isNotEmpty()) { Text("Clear") }
                }
                val summary = if (files.isEmpty()) "" else "${files.size} file${if (files.size == 1) "" else "s"} · ${humanSize(sizes.values.sum())}"
                val line = listOfNotNull(summary.takeIf { it.isNotEmpty() }, status).joinToString("  ·  ")
                if (line.isNotEmpty()) Text(line, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(top = 6.dp))
                if (files.isEmpty()) {
                    Text(
                        "This project has no files yet. Ask CodePress to create something, or import files from your phone.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(files, key = { it }) { path ->
                            Row(
                                Modifier.fillMaxWidth().clickable { open = path }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(path.substringAfterLast('/'), fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val dir = path.substringBeforeLast('/', "")
                                    Text(
                                        (if (dir.isNotEmpty()) "$dir · " else "") + humanSize(sizes[path] ?: 0L),
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isPreviewable(path)) IconAction(Icons.Rounded.PlayCircle, "Run / preview", { vm.previewFile(path) }, tint = MaterialTheme.colorScheme.primary)
                                IconAction(Icons.Rounded.Download, "Download", { download(path) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear this project's files?") },
            text = { Text("This deletes every file the agent created or you imported in this project. Download a ZIP first if you want to keep them.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearWorkspace() }) { Text("Delete everything") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

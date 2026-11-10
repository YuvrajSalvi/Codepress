package com.codepress.app.ui

import androidx.compose.material.icons.rounded.Code

import androidx.compose.material.icons.rounded.LockOpen

import androidx.compose.ui.graphics.Color

import androidx.compose.material3.OutlinedTextFieldDefaults

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.foundation.BorderStroke

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.agent.ShellOrigin
import com.codepress.app.agent.ShellState
import com.codepress.app.agent.str
import com.codepress.app.data.Project
import com.codepress.app.data.humanSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, project: Project, onSettings: () -> Unit, onOpenShell: () -> Unit, onOpenFiles: () -> Unit) {
    val uiItems by vm.items.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val rev by vm.revision.collectAsStateWithLifecycle()
    val problem = remember(rev) { vm.settings.configProblem() }
    val subtitle = remember(rev) { vm.activeLabel() }
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val download = rememberFileDownloader(vm)
    val listState = rememberLazyListState()
    val view = LocalView.current
    val lastAssistantId = uiItems.lastOrNull { it is UiItem.Assistant }?.id
    val draft by vm.draft.collectAsStateWithLifecycle()
    val alwaysAllow by vm.allowAlwaysFlow.collectAsStateWithLifecycle()
    LaunchedEffect(draft) { draft?.let { input = it; vm.consumeDraft() } }
    val attention = !running && uiItems.lastOrNull().let { it is UiItem.Notice && it.isError }

    // Keep the screen on while the agent works, so Android does not freeze the app mid-task.
    LaunchedEffect(running) { view.keepScreenOn = running }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "All projects", { vm.closeProject() }) },
                title = {
                    Column {
                        Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val (dot, word) = when {
                            running -> "●" to "Working"
                            attention -> "●" to "Attention"
                            else -> "●" to "Ready"
                        }
                        Text(
                            "$dot $word · $subtitle",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconAction(Icons.Rounded.DeleteSweep, "Clear chat", { confirmClear = true }, enabled = uiItems.isNotEmpty() && !running)
                    IconAction(Icons.Rounded.Settings, "Settings", onSettings)
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (problem != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(problem, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onSettings) { Text("Settings") }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (uiItems.isEmpty()) {
                    EmptyState(Modifier.align(Alignment.Center))
                } else {
                    // reverseLayout keeps the newest message pinned to the bottom while it streams in.
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(uiItems.asReversed(), key = { it.id }) { item ->
                            when (item) {
                                is UiItem.User -> UserMessage(item, vm, running)
                                is UiItem.Assistant -> AssistantMessage(
                                    item,
                                    vm,
                                    isLast = item.id == lastAssistantId,
                                    running = running,
                                    onRun = { cmd -> vm.runInShell(cmd); onOpenShell() },
                                    onPreview = { code, lang -> vm.previewCode(code, lang) },
                                )
                                is UiItem.Tool -> ToolCard(item, vm, onOpenShell)
                                is UiItem.Notice -> NoticeText(item)
                                is UiItem.Files -> FilesCard(item, vm, download, onOpenFiles)
                            }
                        }
                    }
                }
            }

            status?.let { LiveStatus(it) }

            ModelSwitcher(
                vm,
                enabled = !running,
                onManage = onSettings,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask CodePress to build or fix something…", color = Color.White.copy(alpha = 0.72f)) },
                    maxLines = 6,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Black,
                        unfocusedContainerColor = Color.Black,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                if (running) {
                    PrimaryIconButton(Icons.Rounded.Stop, "Stop", vm::stop)
                } else {
                    PrimaryIconButton(
                        Icons.AutoMirrored.Rounded.Send,
                        "Send",
                        {
                            vm.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear this chat?") },
            text = { Text("The conversation of “${project.name}” is deleted. Its files stay.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearChat() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("CodePress", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "An AI coding agent in your pocket. It reads, writes and edits files in its workspace and runs shell commands. You see everything it does. Try:\n\n“Create a small HTML page with a counter button.”",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Text(TILAK_LINE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = ComposerBlue,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(text, Modifier.padding(12.dp), color = Color.White)
            }
        }
    }
}

@Composable
fun AssistantText(text: String, onRun: (String) -> Unit, onPreview: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.split("```").forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val hasLang = part.contains('\n')
                val lang = if (hasLang) part.substringBefore('\n').trim().substringBefore(' ') else ""
                val body = (if (hasLang) part.substringAfter('\n') else part).trimEnd('\n')
                CodeBlock(body, lang, onRun, onPreview = { onPreview(body, lang) })
            } else if (part.isNotBlank()) {
                MarkdownText(part.trim())
            }
        }
    }
}

/** Files and folders the AI created, changed or deleted. Each can be opened, run or downloaded right from the chat. */
@Composable
private fun FilesCard(item: UiItem.Files, vm: ChatViewModel, download: (String) -> Unit, onOpenFiles: () -> Unit) {
    val all = item.created.map { it to "new" } + item.updated.map { it to "updated" } + item.deleted.map { it to "deleted" }
    val shown = all.take(8)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp, end = 4.dp)) {
            val folders = item.created.count { it.endsWith("/") }
            val newFiles = item.created.size - folders
            val summary = buildList {
                if (newFiles > 0) add("$newFiles file${if (newFiles == 1) "" else "s"} created")
                if (folders > 0) add("$folders folder${if (folders == 1) "" else "s"} created")
                if (item.updated.isNotEmpty()) add("${item.updated.size} updated")
                if (item.deleted.isNotEmpty()) add("${item.deleted.size} deleted")
            }.joinToString(" · ")
            Text(summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            shown.forEach { (path, kind) ->
                val isDir = path.endsWith("/")
                val clean = path.trimEnd('/')
                val size = remember(item.id, path) { if (isDir || kind == "deleted") "" else runCatching { humanSize(vm.workspace.resolve(clean).length()) }.getOrDefault("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FileIcon(clean, isDir, 34.dp)
                    Column(Modifier.weight(1f).padding(start = 6.dp, top = 4.dp, bottom = 4.dp)) {
                        Text(clean.substringAfterLast('/') + if (isDir) "/" else "", fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val dir = clean.substringBeforeLast('/', "")
                        Text(
                            (if (dir.isNotEmpty()) "$dir · " else "") + listOf(size, if (kind == "new") "" else kind).filter { it.isNotEmpty() }.joinToString(" · "),
                            fontSize = 11.sp,
                            color = if (kind == "deleted") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (kind != "deleted") {
                        if (!isDir && isPreviewable(clean)) IconAction(Icons.Rounded.PlayCircle, "Run / preview", { vm.previewFile(clean) }, tint = MaterialTheme.colorScheme.primary)
                        if (!isDir) IconAction(Icons.Rounded.Code, "Open in editor", { vm.openInEditor(clean) })
                        IconAction(Icons.Rounded.Download, if (isDir) "Download folder as ZIP" else "Download", { download(path) })
                    }
                }
            }
            TextButton(onClick = onOpenFiles) { Text(if (all.size > shown.size) "+${all.size - shown.size} more in Files" else "Open in Files", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun NoticeText(item: UiItem.Notice) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (item.isError) "✕ " else "") + item.text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.isError) CopyButton(item.text)
    }
}

@Composable
private fun ToolCard(item: UiItem.Tool, vm: ChatViewModel, onOpenShell: () -> Unit) {
    val call = item.call
    val isCommand = call.name == "run_command"
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val entries = if (isCommand) vm.shell.entries.collectAsStateWithLifecycle().value else emptyList()
    val entry = if (isCommand) entries.lastOrNull { it.origin == ShellOrigin.AI && it.tag == call.id && it.id > item.shellFrom } else null

    val active = item.status == ToolStatus.QUEUED || item.status == ToolStatus.AWAITING || item.status == ToolStatus.RUNNING
    val (glyph, word, color) = when (item.status) {
        ToolStatus.QUEUED -> Triple("…", "Queued", MaterialTheme.colorScheme.onSurfaceVariant)
        ToolStatus.AWAITING -> Triple("?", "Needs approval", MaterialTheme.colorScheme.primary)
        ToolStatus.RUNNING -> Triple("●", "Running", MaterialTheme.colorScheme.primary)
        ToolStatus.DONE -> Triple("✓", "Done", MaterialTheme.colorScheme.secondary)
        ToolStatus.FAILED -> Triple("✕", "Failed", MaterialTheme.colorScheme.error)
        ToolStatus.DENIED -> Triple("■", "Denied", MaterialTheme.colorScheme.error)
    }
    val target = toolTarget(call)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(item.status, color)
                    Spacer(Modifier.width(6.dp))
                    Text(word, color = color, fontSize = 12.sp)
                }
                val ms = entry?.durationMs ?: item.durationMs
                if (ms > 0 && !active) Text(formatDuration(ms), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (active) runningLabel(call) else doneLabel(call), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)

            if (isCommand) {
                Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$ $target",
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                            maxLines = if (item.status == ToolStatus.AWAITING) 12 else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        CopyButton(target)
                    }
                }
            } else if (target.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(target, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (call.name != "search_files") CopyButton(target, "Copy path")
                }
            }

            if (item.status == ToolStatus.AWAITING) {
                when (call.name) {
                    "write_file" -> CodeBlock((call.arguments.str("content") ?: "").take(1500), "new content")
                    "edit_file" -> {
                        CodeBlock((call.arguments.str("old_text") ?: "").take(700), "remove")
                        CodeBlock((call.arguments.str("new_text") ?: "").take(700), "add")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.answerApproval(true) }) { Text("Allow") }
                    OutlinedButton(onClick = { vm.answerApproval(false) }) { Text("Deny") }
                    TextButton(onClick = { vm.allowAlways() }) { Text("Always allow", fontSize = 12.sp) }
                }
            }

            val out = entry?.output ?: item.output.orEmpty()
            if (out.isNotEmpty()) {
                val long = out.length > 400 || out.lines().size > 6
                Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            if (expanded || !long) out.take(20_000) else lastLines(out, 6),
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (long) IconAction(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Collapse output" else "View full output", { expanded = !expanded })
                    Spacer(Modifier.weight(1f))
                    CopyButton(out, "Copy output")
                }
            }

            if (entry != null && entry.exitCode != null) {
                Text("Exit code ${entry.exitCode} · ${formatDuration(entry.durationMs)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry != null && entry.state == ShellState.RUNNING) {
                IconAction(Icons.Rounded.Stop, "Stop command", { vm.stopShell(entry.id) }, tint = MaterialTheme.colorScheme.error)
            } else if (isCommand && entry != null && !active) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconAction(Icons.Rounded.Refresh, "Run again", { vm.runInShell(target); onOpenShell() })
                    IconAction(Icons.Rounded.Terminal, "Open Shell", onOpenShell)
                }
            }
        }
    }
}

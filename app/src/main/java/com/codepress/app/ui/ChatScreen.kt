package com.codepress.app.ui

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onSettings: () -> Unit, onOpenShell: () -> Unit) {
    val uiItems by vm.items.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val rev by vm.revision.collectAsStateWithLifecycle()
    val problem = remember(rev) { vm.settings.configProblem() }
    val subtitle = remember(rev) { vm.activeLabel() }
    var input by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val view = LocalView.current
    val attention = !running && uiItems.lastOrNull().let { it is UiItem.Notice && it.isError }

    // Keep the screen on while the agent works, so Android does not freeze the app mid-task.
    LaunchedEffect(running) { view.keepScreenOn = running }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text("CodePress", style = MaterialTheme.typography.titleMedium)
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
                    TextButton(onClick = { menu = true }) { Text("Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("New chat") }, onClick = { menu = false; vm.newChat() })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
                    }
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
                                is UiItem.User -> UserBubble(item.text)
                                is UiItem.Assistant -> AssistantText(item.text) { cmd -> vm.runInShell(cmd); onOpenShell() }
                                is UiItem.Tool -> ToolCard(item, vm, onOpenShell)
                                is UiItem.Notice -> NoticeText(item)
                            }
                        }
                    }
                }
            }

            status?.let { LiveStatus(it) }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask CodePress to build or fix something…") },
                    maxLines = 6,
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (running) {
                    Button(onClick = vm::stop) { Text("Stop") }
                } else {
                    Button(
                        onClick = {
                            vm.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                    ) { Text("Send") }
                }
            }
        }
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
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun AssistantText(text: String, onRun: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.split("```").forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val hasLang = part.contains('\n')
                val lang = if (hasLang) part.substringBefore('\n').trim() else ""
                val body = (if (hasLang) part.substringAfter('\n') else part).trimEnd('\n')
                CodeBlock(body, lang, onRun)
            } else if (part.isNotBlank()) {
                SelectionContainer { Text(part.trim(), style = MaterialTheme.typography.bodyLarge) }
            }
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

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$glyph $word", color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
                val ms = entry?.durationMs ?: item.durationMs
                if (ms > 0 && !active) Text(formatDuration(ms), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (active) runningLabel(call) else doneLabel(call), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)

            if (isCommand) {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                }
            }

            val out = entry?.output ?: item.output.orEmpty()
            if (out.isNotEmpty()) {
                val long = out.length > 400 || out.lines().size > 6
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                    if (long) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse" else "View full output", fontSize = 12.sp) }
                    Spacer(Modifier.weight(1f))
                    CopyButton(out, "Copy output")
                }
            }

            if (entry != null && entry.exitCode != null) {
                Text("Exit code ${entry.exitCode} · ${formatDuration(entry.durationMs)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry != null && entry.state == ShellState.RUNNING) {
                OutlinedButton(onClick = { vm.stopShell(entry.id) }) { Text("Stop command") }
            } else if (isCommand && entry != null && !active) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.runInShell(target); onOpenShell() }) { Text("Run again") }
                    TextButton(onClick = onOpenShell) { Text("Open Shell") }
                }
            }
        }
    }
}

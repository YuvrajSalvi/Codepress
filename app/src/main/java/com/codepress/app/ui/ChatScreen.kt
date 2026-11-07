package com.codepress.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.agent.ToolCall
import com.codepress.app.agent.str

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onSettings: () -> Unit, onFiles: () -> Unit) {
    val uiItems by vm.items.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val rev by vm.revision.collectAsStateWithLifecycle()
    val problem = remember(rev) { vm.settings.configProblem() }
    val subtitle = remember(rev) { vm.activeLabel() }
    var input by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val view = LocalView.current

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
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { menu = true }) { Text("Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("New chat") }, onClick = { menu = false; vm.newChat() })
                        DropdownMenuItem(text = { Text("Files") }, onClick = { menu = false; onFiles() })
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
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(uiItems.asReversed(), key = { it.id }) { item ->
                            when (item) {
                                is UiItem.User -> UserBubble(item.text)
                                is UiItem.Assistant -> AssistantText(item.text)
                                is UiItem.Tool -> ToolCard(item, vm::answerApproval)
                                is UiItem.Notice -> NoticeText(item)
                            }
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
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
            "An AI coding agent in your pocket. It can read, write and edit files in its workspace and run shell commands. Try:\n\n“Create a small HTML page with a counter button.”",
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
private fun AssistantText(text: String) {
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            text.split("```").forEachIndexed { i, part ->
                if (i % 2 == 1) {
                    CodeBlock(part.substringAfter('\n', part).trimEnd())
                } else if (part.isNotBlank()) {
                    Text(part.trim(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            code,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NoticeText(item: UiItem.Notice) {
    Text(
        item.text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun summary(c: ToolCall): String = when (c.name) {
    "run_command" -> "$ " + (c.arguments.str("command") ?: "")
    "search_files" -> "search_files  " + (c.arguments.str("pattern") ?: "")
    else -> c.name + "  " + (c.arguments.str("path") ?: "")
}

@Composable
private fun ToolCard(item: UiItem.Tool, onApproval: (Boolean) -> Unit) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val (glyph, color) = when (item.status) {
        ToolStatus.QUEUED -> "…" to MaterialTheme.colorScheme.onSurfaceVariant
        ToolStatus.AWAITING -> "?" to MaterialTheme.colorScheme.primary
        ToolStatus.RUNNING -> "▶" to MaterialTheme.colorScheme.primary
        ToolStatus.DONE -> "✓" to MaterialTheme.colorScheme.secondary
        ToolStatus.FAILED -> "✗" to MaterialTheme.colorScheme.error
        ToolStatus.DENIED -> "⊘" to MaterialTheme.colorScheme.error
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(glyph, color = color, modifier = Modifier.width(22.dp))
                Text(
                    summary(item.call),
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    maxLines = if (item.status == ToolStatus.AWAITING) 12 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.status == ToolStatus.AWAITING) {
                Spacer(Modifier.height(6.dp))
                when (item.call.name) {
                    "write_file" -> CodeBlock((item.call.arguments.str("content") ?: "").take(1500))
                    "edit_file" -> {
                        CodeBlock("- " + (item.call.arguments.str("old_text") ?: "").take(700))
                        CodeBlock("+ " + (item.call.arguments.str("new_text") ?: "").take(700))
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onApproval(true) }) { Text("Allow") }
                    OutlinedButton(onClick = { onApproval(false) }) { Text("Deny") }
                }
            }
            val out = item.output
            if (!out.isNullOrEmpty()) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide output" else "Show output") }
                if (expanded) CodeBlock(out.take(4000))
            }
        }
    }
}

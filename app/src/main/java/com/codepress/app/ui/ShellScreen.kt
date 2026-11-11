package com.codepress.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Refresh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.agent.ShellEntry
import com.codepress.app.agent.ShellOrigin
import com.codepress.app.agent.ShellState

private val QUICK_COMMANDS = listOf(
    "ls -la", "pwd", "cat ", "mkdir ", "git status", "git add .", "git commit -m \"\"", "git push", "git clone ",
    "apk update", "apk add ", "apk del ", "apk search ", "python3 ", "node ", "nano ",
)

private val HELP = listOf(
    "Files and folders" to "ls  cd  pwd  cat  mkdir  cp  mv  rm",
    "Git" to "git status\ngit add .\ngit commit -m \"message\"\ngit push\ngit clone <link>",
    "Install tools (Alpine apk)" to "apk update\napk add <name>\napk del <name>\napk search <name>",
    "Edit a file" to "apk add nano   then   nano <file>",
    "Tip" to "Tap a chip above the box to fill in a command, then edit it and tap Run. Commands from the AI show up here marked “AI →”.",
)

/** Shell tab: a real interactive terminal, plus the log of commands the AI ran. */
@Composable
fun ShellScreen(vm: ChatViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Terminal") })
            androidx.compose.material3.FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("AI command log") })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (tab == 0) TerminalTab(vm) else ShellLogContent(vm)
    }
}

@Composable
private fun ShellLogContent(vm: ChatViewModel) {
    val entries by vm.shell.entries.collectAsStateWithLifecycle()
    val cwd by vm.shell.cwdLabel.collectAsStateWithLifecycle()
    val history by vm.shell.history.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun submit() {
        val cmd = input.trim()
        if (cmd.isEmpty()) return
        vm.runInShell(cmd)
        input = ""
    }

    Column(Modifier.fillMaxSize().padding(top = 0.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI command log", style = MaterialTheme.typography.titleMedium)
                Text(cwd, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconAction(Icons.Rounded.Info, "Terminal help", { showHelp = true })
            IconAction(Icons.Rounded.History, "Command history", { showHistory = true })
            IconAction(Icons.Rounded.Delete, "Clear screen", vm::clearShell)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty()) {
                Text(
                    "Run any command, for example:\n\nls\npwd\ncd src\n\nTap the chips above the box for common commands. Commands the AI runs also show up here, marked “AI →”. The shell stays inside this project's files.",
                    modifier = Modifier.padding(20.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(entries.asReversed(), key = { it.id }) { e ->
                        ShellEntryView(e, onRerun = { vm.runInShell(e.command) }, onStop = { vm.stopShell(e.id) })
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Quick commands: tap to fill the box, then edit and run.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK_COMMANDS.forEach { cmd ->
                AssistChip(onClick = { input = cmd }, label = { Text(cmd.trim(), fontFamily = FontFamily.Monospace, fontSize = 12.sp) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                prefix = { Text("$ ", fontFamily = FontFamily.Monospace) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                placeholder = { Text("command") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
            Spacer(Modifier.width(8.dp))
            PrimaryIconButton(Icons.Rounded.PlayArrow, "Run command", { submit() }, enabled = input.isNotBlank())
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Terminal help") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HELP.forEach { (title, body) ->
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(body, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } },
        )
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("Command history") },
            text = {
                if (history.isEmpty()) {
                    Text("No commands yet.")
                } else {
                    LazyColumn {
                        items(history) { cmd ->
                            Row(Modifier.fillMaxWidth().clickable { input = cmd; showHistory = false }, verticalAlignment = Alignment.CenterVertically) {
                                Text(cmd, modifier = Modifier.weight(1f).padding(vertical = 10.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 2)
                                CopyButton(cmd)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } },
            dismissButton = { if (history.isNotEmpty()) TextButton(onClick = { vm.shell.deleteHistory() }) { Text("Delete history") } },
        )
    }
}

@Composable
private fun ShellEntryView(e: ShellEntry, onRerun: () -> Unit, onStop: () -> Unit) {
    var all by rememberSaveable(e.id) { mutableStateOf(false) }
    val isAi = e.origin == ShellOrigin.AI
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            (if (isAi) "AI → $ " else "$ ") + e.command,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = if (isAi) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
        )
        if (e.output.isNotEmpty()) {
            val lines = e.output.lines()
            val tooLong = lines.size > 60
            SelectionContainer {
                Text(
                    if (all || !tooLong) e.output else lines.takeLast(60).joinToString("\n"),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            if (tooLong) TextButton(onClick = { all = !all }) { Text(if (all) "Show last 60 lines" else "Show all ${lines.size} lines", fontSize = 12.sp) }
        }
        val (glyph, word, color) = when (e.state) {
            ShellState.RUNNING -> Triple("●", "Running", MaterialTheme.colorScheme.primary)
            ShellState.COMPLETED -> Triple("✓", "Completed", MaterialTheme.colorScheme.secondary)
            ShellState.FAILED -> Triple("✕", "Failed", MaterialTheme.colorScheme.error)
            ShellState.STOPPED -> Triple("■", "Stopped", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$glyph $word" + (e.exitCode?.let { " · exit $it" } ?: "") + " · ${formatDuration(e.durationMs)} · ${e.cwd}",
                fontSize = 11.sp,
                color = color,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (e.state == ShellState.RUNNING) {
                IconAction(Icons.Rounded.Stop, "Stop command", onStop, tint = MaterialTheme.colorScheme.error)
            } else {
                CopyButton(e.command, "Copy command")
                if (e.output.isNotEmpty()) CopyButton(e.output, "Copy output")
                IconAction(Icons.Rounded.Refresh, "Run again", onRerun)
            }
        }
    }
}

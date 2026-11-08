package com.codepress.app.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
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

@Composable
fun ShellScreen(vm: ChatViewModel) {
    val entries by vm.shell.entries.collectAsStateWithLifecycle()
    val cwd by vm.shell.cwdLabel.collectAsStateWithLifecycle()
    val history by vm.shell.history.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun submit() {
        val cmd = input.trim()
        if (cmd.isEmpty()) return
        vm.runInShell(cmd)
        input = ""
    }

    Column(Modifier.fillMaxSize().padding(top = 0.dp)) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Shell", style = MaterialTheme.typography.titleMedium)
                Text(cwd, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showHistory = true }) { Text("History") }
            TextButton(onClick = vm::clearShell) { Text("Clear") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty()) {
                Text(
                    "Run any command, for example:\n\nls\npwd\ncd src\n\nCommands the AI runs also show up here, marked “AI →”. The shell stays inside the project workspace.",
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
            Button(onClick = { submit() }, enabled = input.isNotBlank()) { Text("Run") }
        }
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
                OutlinedButton(onClick = onStop) { Text("Stop") }
            } else {
                CopyButton(e.command, "Copy command")
                if (e.output.isNotEmpty()) CopyButton(e.output, "Copy output")
                TextButton(onClick = onRerun, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text("Run again", fontSize = 12.sp) }
            }
        }
    }
}

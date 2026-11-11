package com.codepress.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.agent.FsEntry
import com.codepress.app.agent.ShellState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(vm: ChatViewModel, onOpenChat: () -> Unit) {
    val ed = vm.editor
    val tab = ed.current
    var showTree by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    var gotoOpen by remember { mutableStateOf(false) }
    var saveAsOpen by remember { mutableStateOf(false) }
    var newFileOpen by remember { mutableStateOf(false) }
    var askOpen by remember { mutableStateOf(false) }
    var closeAsk by remember { mutableStateOf<Int?>(null) }
    val missing by vm.runtimeMissing.collectAsStateWithLifecycle()
    val runId by vm.editorRunId.collectAsStateWithLifecycle()
    val download = rememberFileDownloader(vm)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                FileTree(vm, Modifier.width(250.dp).fillMaxSize().background(MaterialTheme.colorScheme.surface)) { vm.openInEditor(it) }
            }
            Column(Modifier.weight(1f).fillMaxSize().statusBarsPadding()) {
                // ---- header
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!wide) IconAction(Icons.Rounded.FolderOpen, "File tree", { showTree = true })
                    Text(
                        if (tab == null) "Editor" else tab.name + if (tab.dirty) " *" else "",
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    if (tab != null) {
                        IconAction(Icons.AutoMirrored.Rounded.Undo, "Undo", { tab.undo() }, enabled = tab.undo.isNotEmpty())
                        IconAction(Icons.AutoMirrored.Rounded.Redo, "Redo", { tab.redo() }, enabled = tab.redo.isNotEmpty())
                        IconAction(Icons.Rounded.Save, "Save", { vm.saveTab(tab) }, enabled = tab.dirty, tint = if (tab.dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        IconAction(Icons.Rounded.Search, "Find and replace", { findOpen = !findOpen })
                        IconAction(Icons.Rounded.PlayArrow, "Run", { vm.runFile(tab) }, tint = MaterialTheme.colorScheme.secondary)
                        IconAction(Icons.Rounded.MoreVert, "More", { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Ask AI…") }, leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) }, onClick = { menu = false; askOpen = true })
                            DropdownMenuItem(text = { Text("Save as…") }, onClick = { menu = false; saveAsOpen = true })
                            DropdownMenuItem(text = { Text("Go to line…") }, onClick = { menu = false; gotoOpen = true })
                            DropdownMenuItem(text = { Text(if (wrap) "Word wrap: on" else "Word wrap: off") }, onClick = { menu = false; wrap = !wrap })
                            DropdownMenuItem(text = { Text("Select all") }, onClick = { menu = false; tab.value = tab.value.copy(selection = TextRange(0, tab.value.text.length)) })
                            DropdownMenuItem(text = { Text("Copy all") }, onClick = { menu = false; vm.copyText(tab.value.text, "Code copied") })
                            if (languageOf(tab.path) == "json") DropdownMenuItem(text = { Text("Format JSON") }, onClick = { menu = false; vm.formatJson(tab) })
                            DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; vm.saveTab(tab); download(tab.path) })
                            DropdownMenuItem(text = { Text("Open in Files") }, onClick = { menu = false; vm.openInFiles(tab.path) })
                        }
                    }
                }
                // ---- tabs
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ed.tabs.forEachIndexed { i, t ->
                        val selected = i == ed.active
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.padding(end = 6.dp).clickable { ed.active = i },
                        ) {
                            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(t.name + if (t.dirty) " *" else "", fontSize = 13.sp, maxLines = 1)
                                IconAction(Icons.Rounded.Close, "Close ${t.name}", { if (t.dirty) closeAsk = i else ed.close(i) })
                            }
                        }
                    }
                    IconAction(Icons.Rounded.Add, "New file", { newFileOpen = true })
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (tab != null && findOpen) FindBar(tab) { findOpen = false }

                // ---- code
                if (tab == null) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("No file open", style = MaterialTheme.typography.titleMedium)
                        Text("Open a file from the file tree or the Files tab, or create a new one.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { newFileOpen = true }) { Text("New file") }
                            if (!wide) TextButton(onClick = { showTree = true }) { Text("Browse files") }
                        }
                    }
                } else {
                    val lang = languageOf(tab.path)
                    val lines = remember(tab.value.text.count { it == '\n' }) { tab.value.text.count { it == '\n' } + 1 }
                    val numbers = remember(lines) { (1..lines).joinToString("\n") }
                    val hScroll = rememberScrollState()
                    val bracketBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                        if (!wrap) {
                            Text(
                                numbers,
                                modifier = Modifier.width((lines.toString().length * 9 + 18).dp).padding(end = 6.dp),
                                style = CodeTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                textAlign = TextAlign.End,
                            )
                        }
                        BasicTextField(
                            value = tab.value,
                            onValueChange = { nv -> tab.edit(autoEdit(tab.value, nv, lang)) },
                            modifier = Modifier
                                .weight(1f)
                                .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll))
                                .defaultMinSize(minWidth = 280.dp, minHeight = 320.dp),
                            textStyle = CodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            visualTransformation = remember(lang, tab.value.selection.start) { SyntaxTransformation(lang, tab.value.selection.start, bracketBg) },
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                        )
                    }
                }

                // ---- run output
                if (runId != null) RunPanel(vm, runId!!)

                // ---- shortcut bar
                if (tab != null) {
                    val keys = remember(tab.path) { shortcutKeys(languageOf(tab.path)) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp)) {
                        keys.forEach { (label, text, back) ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(end = 6.dp).clickable { tab.edit(insertAtCursor(tab.value, text, back)) },
                            ) {
                                Text(label, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        if (showTree && !wide) {
            ModalBottomSheet(onDismissRequest = { showTree = false }) {
                FileTree(vm, Modifier.fillMaxWidth().heightIn(max = 520.dp)) { showTree = false; vm.openInEditor(it) }
            }
        }
    }

    // ---- dialogs
    if (newFileOpen) AskNameDialog("New file", "", "e.g. main.py or src/app.js", { newFileOpen = false }) { name ->
        newFileOpen = false
        val dir = tab?.path?.substringBeforeLast('/', "") ?: ""
        vm.fsCreateFile(dir, name, open = true)
    }
    if (saveAsOpen && tab != null) AskNameDialog("Save as", tab.path, "path/name.ext", { saveAsOpen = false }) { name -> saveAsOpen = false; vm.saveTabAs(tab, name) }
    if (gotoOpen && tab != null) AskNameDialog("Go to line", "", "line number", { gotoOpen = false }) { n -> gotoOpen = false; n.trim().toIntOrNull()?.let { tab.goToLine(it) } }
    if (askOpen && tab != null) AskAiDialog(tab, onDismiss = { askOpen = false }) { prompt -> askOpen = false; vm.askAiAbout(prompt); onOpenChat() }
    closeAsk?.let { i ->
        val t = ed.tabs.getOrNull(i)
        if (t == null) closeAsk = null else AlertDialog(
            onDismissRequest = { closeAsk = null },
            title = { Text("Save ${t.name}?") },
            text = { Text("You have unsaved changes.") },
            confirmButton = { TextButton(onClick = { vm.saveTab(t); ed.close(i); closeAsk = null }) { Text("Save") } },
            dismissButton = { Row { TextButton(onClick = { ed.close(i); closeAsk = null }) { Text("Discard") }; TextButton(onClick = { closeAsk = null }) { Text("Cancel") } } },
        )
    }
    missing?.let { (label, names) ->
        AlertDialog(
            onDismissRequest = { vm.clearRuntimeMissing() },
            title = { Text("$label is not installed") },
            text = {
                Text(
                    "CodePress runs commands with Android's own shell, and it does not include ${names.joinToString(", ")}. " +
                        "CodePress has no package manager yet, so it cannot install it for you. " +
                        "You can still write the code here, run HTML/SVG in Preview, or run it on another device.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.clearRuntimeMissing() }) { Text("OK") } },
        )
    }
}

@Composable
fun AskNameDialog(title: String, initial: String, hint: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text(hint) }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onOk(text.trim()) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskAiDialog(tab: EditorTab, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val selected = tab.value.let { if (it.selection.collapsed) "" else it.text.substring(it.selection.min, it.selection.max) }
    var text by remember { mutableStateOf("") }
    val actions = listOf("Explain this code", "Fix the bugs in this code", "Improve this code", "Refactor this code", "Add comments", "Generate tests for this code", "Find a bug", "Write a function that…")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ask AI") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (selected.isEmpty()) "No text selected: the AI gets the file path only and reads what it needs." else "The AI receives your ${selected.lines().size} selected line(s).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions.forEach { a -> AssistChip(onClick = { text = a }, label = { Text(a, fontSize = 12.sp) }) } }
                OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("What should the AI do?") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val lang = languageOf(tab.path)
                val prompt = buildString {
                    append(text.trim()).append("\n\nFile: ").append(tab.path)
                    if (selected.isNotEmpty()) append("\nSelected code:\n```").append(lang).append('\n').append(selected).append("\n```")
                    else append("\n(Read the file with read_file if you need its content. Do not read other files unless required.)")
                }
                onSend(prompt)
            }, enabled = text.isNotBlank()) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FindBar(tab: EditorTab, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    val hits = remember(tab.value.text, query) { findAll(tab.value.text, query) }
    fun select(i: Int) {
        tab.value = tab.value.copy(selection = TextRange(hits[i], hits[i] + query.length))
    }
    fun next() {
        if (hits.isEmpty()) return
        select(hits.indexOfFirst { it >= tab.value.selection.max }.let { if (it < 0) 0 else it })
    }
    fun prev() {
        if (hits.isEmpty()) return
        select(hits.indexOfLast { it < tab.value.selection.min }.let { if (it < 0) hits.lastIndex else it })
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f))
            Text("${hits.size}", Modifier.padding(horizontal = 8.dp), fontSize = 12.sp)
            IconAction(Icons.Rounded.KeyboardArrowUp, "Previous match", { prev() })
            IconAction(Icons.Rounded.KeyboardArrowDown, "Next match", { next() })
            IconAction(Icons.Rounded.Close, "Close find", onClose)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = repl, onValueChange = { repl = it }, placeholder = { Text("Replace with") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val sel = tab.value.selection
                if (!sel.collapsed && tab.value.text.substring(sel.min, sel.max).equals(query, ignoreCase = true)) {
                    val t = tab.value.text.replaceRange(sel.min, sel.max, repl)
                    tab.edit(TextFieldValue(t, TextRange(sel.min + repl.length)))
                }
                next()
            }, enabled = hits.isNotEmpty()) { Text("Replace") }
            TextButton(onClick = { tab.edit(TextFieldValue(tab.value.text.replace(query, repl, ignoreCase = true))) }, enabled = hits.isNotEmpty()) { Text("All") }
        }
    }
}

@Composable
private fun RunPanel(vm: ChatViewModel, id: Long) {
    val entries by vm.shell.entries.collectAsStateWithLifecycle()
    val e = entries.firstOrNull { it.id == id } ?: return
    Surface(color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
        Column {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val (word, color) = when (e.state) {
                    ShellState.RUNNING -> "RUNNING" to MaterialTheme.colorScheme.primary
                    ShellState.COMPLETED -> "DONE" to MaterialTheme.colorScheme.secondary
                    ShellState.FAILED -> "ERROR" to MaterialTheme.colorScheme.error
                    ShellState.STOPPED -> "STOPPED" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(word, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (e.state == ShellState.RUNNING) IconAction(Icons.Rounded.Stop, "Stop", { vm.stopShell(e.id) }, tint = MaterialTheme.colorScheme.error)
                else if (e.state == ShellState.FAILED) TextButton(onClick = { vm.askAiToFix(e.command, e.output) }) { Text("Fix with AI", fontSize = 12.sp) }
                CopyButton(e.output, "Copy output")
                IconAction(Icons.Rounded.Close, "Clear output", { vm.clearEditorRun() })
            }
            Text("$ ${e.command}", Modifier.padding(horizontal = 12.dp), style = CodeTextStyle, color = MaterialTheme.colorScheme.primary, maxLines = 2)
            SelectionContainer(Modifier.weight(1f, fill = false)) {
                Text(
                    e.output.ifEmpty { if (e.state == ShellState.RUNNING) "…" else "(no output)" },
                    modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
                    style = CodeTextStyle.copy(fontSize = 12.sp, lineHeight = 17.sp),
                )
            }
        }
    }
}

/** Collapsible project tree. Tap a folder to expand it, tap a file to open it in the editor. */
@Composable
fun FileTree(vm: ChatViewModel, modifier: Modifier, onOpen: (String) -> Unit) {
    val rev by vm.revision.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val rows = remember(rev, expanded) {
        val out = ArrayList<Pair<FsEntry, Int>>()
        fun walk(dir: String, depth: Int) {
            if (out.size > 1500) return
            for (e in runCatching { vm.fileOps.list(dir) }.getOrDefault(emptyList())) {
                out += e to depth
                if (e.isDir && e.path in expanded) walk(e.path, depth + 1)
            }
        }
        walk("", 0)
        out
    }
    LazyColumn(modifier.padding(vertical = 4.dp)) {
        item { Text("Project files", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (rows.isEmpty()) item { Text("No files yet.", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(rows, key = { it.first.path }) { (e, depth) ->
            Row(
                Modifier.fillMaxWidth().clickable { if (e.isDir) expanded = if (e.path in expanded) expanded - e.path else expanded + e.path else onOpen(e.path) }
                    .padding(start = (8 + depth * 16).dp, top = 2.dp, bottom = 2.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (e.isDir) Icon(if (e.path in expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, null, Modifier.width(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else Spacer(Modifier.width(20.dp))
                FileIcon(e.path, e.isDir, 30.dp)
                Text(e.name, Modifier.padding(start = 6.dp), fontSize = 13.5.sp, maxLines = 1)
            }
        }
    }
}

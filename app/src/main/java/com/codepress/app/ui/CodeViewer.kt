package com.codepress.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val CodeTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)

/** Read-only code with line numbers, syntax colours and search highlights. */
@Composable
fun CodeViewer(text: String, lang: String, query: String = "", modifier: Modifier = Modifier) {
    val styled = remember(text, lang, query) { withMatches(highlight(text, lang), query, Color(0xFFFFD866)) }
    val lines = remember(text) { text.count { it == '\n' } + 1 }
    val numbers = remember(lines) { (1..lines).joinToString("\n") }
    Row(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            numbers,
            modifier = Modifier.width((lines.toString().length * 9 + 18).dp).padding(end = 6.dp, start = 4.dp),
            style = CodeTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.End,
        )
        SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
            Text(styled, style = CodeTextStyle, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(end = 16.dp))
        }
    }
}

/** Inspect the real, current source of a file. Read only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectScreen(vm: ChatViewModel, file: File, title: String, onClose: () -> Unit) {
    val rev by vm.revision.collectAsStateWithLifecycle()
    val source by produceState<String?>(null, file, rev) {
        value = withContext(Dispatchers.IO) {
            try {
                if (file.length() > 2_000_000) "(file is too large to inspect here)" else file.readText()
            } catch (e: Exception) {
                null
            }
        }
    }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text(title, maxLines = 1)
                        Text("Read-only · ${languageOf(file.name)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClose) },
                actions = {
                    IconAction(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Search in code", { searching = !searching; if (!searching) query = "" })
                    source?.let { CopyButton(it, "Copy code") }
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize(), verticalArrangement = Arrangement.Top) {
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search in ${file.name}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    trailingIcon = { if (query.isNotEmpty()) Text("${findAll(source.orEmpty(), query).size}", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodySmall) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val s = source
            if (s == null) Text("Couldn't open code inspector.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            else CodeViewer(s, languageOf(file.name), query)
        }
    }
}


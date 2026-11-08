package com.codepress.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Copies exactly [text] (nothing else) to the clipboard and briefly says "Copied". */
@Composable
fun CopyButton(text: String, label: String = "Copy", modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    TextButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) { Text(if (copied) "Copied" else label, fontSize = 12.sp) }
}

private val SHELL_LANGS = setOf("bash", "sh", "shell", "zsh", "console", "terminal", "cmd")

/** Code block with a language header, a Copy button (raw code only) and, for shell code, a real Run button. */
@Composable
fun CodeBlock(code: String, language: String = "", onRun: ((String) -> Unit)? = null) {
    val isShell = language.lowercase() in SHELL_LANGS
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (language.isBlank()) "Code" else language,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (isShell && onRun != null) {
                    TextButton(onClick = { onRun(code) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text("Run", fontSize = 12.sp) }
                }
                CopyButton(code)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                code,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private val THINKING_WORDS = listOf("Thinking", "Analyzing", "Planning", "Working")

/**
 * Compact live status. Real operations (reading, running, writing...) show their own label.
 * While only waiting for the model, the word rotates slowly so the app never looks frozen.
 */
@Composable
fun LiveStatus(status: AgentStatus, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (true) {
            now = System.currentTimeMillis()
            delay(400)
        }
    }
    val elapsed = ((now - status.since) / 1000).toInt().coerceAtLeast(0)
    val dots = ".".repeat(((now / 400) % 4).toInt())
    val label = if (status.kind == StatusKind.THINKING) THINKING_WORDS[(elapsed / 4) % THINKING_WORDS.size] else status.label
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(label + dots, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
        Text("${elapsed}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

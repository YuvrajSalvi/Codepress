package com.codepress.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val ACTION_SIZE = Modifier.size(34.dp)

/** Long-press anywhere on a message for Copy / Select text / Share. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LongPressMenu(text: String, vm: ChatViewModel, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var select by remember { mutableStateOf(false) }
    Box(modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })) {
        content()
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { androidx.compose.material3.Icon(Icons.Rounded.ContentCopy, null) }, onClick = { menu = false; vm.copyText(text) })
            DropdownMenuItem(text = { Text("Select text") }, onClick = { menu = false; select = true })
            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { androidx.compose.material3.Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; vm.shareText(text) })
        }
    }
    if (select) SelectTextDialog(text) { select = false }
}

@Composable
fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select text") },
        text = { SelectionContainer { Text(text, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun firstCodeBlock(text: String): Pair<String, String>? {
    val parts = text.split("```")
    if (parts.size < 3) return null
    val part = parts[1]
    val hasLang = part.contains('\n')
    val lang = if (hasLang) part.substringBefore('\n').trim().substringBefore(' ') else ""
    return lang to (if (hasLang) part.substringAfter('\n') else part).trimEnd('\n')
}

/** An AI answer with its action row: Copy, Like, Dislike, Read aloud, Share and a ⋮ menu. */
@Composable
fun AssistantMessage(item: UiItem.Assistant, vm: ChatViewModel, isLast: Boolean, running: Boolean, onRun: (String) -> Unit, onPreview: (String, String) -> Unit) {
    val reactions by vm.reactions.collectAsStateWithLifecycle()
    val speaking by vm.speakingId.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var select by remember { mutableStateOf(false) }
    val reaction = reactions[item.id] ?: 0
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        LongPressMenu(item.text, vm) { AssistantText(item.text, onRun, onPreview) }
        if (!item.streaming) {
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconAction(Icons.Rounded.ContentCopy, "Copy", { vm.copyText(item.text) }, ACTION_SIZE)
                IconAction(Icons.Rounded.ThumbUp, "Like", { vm.react(item.id, 1) }, ACTION_SIZE, tint = if (reaction == 1) accent else muted)
                IconAction(Icons.Rounded.ThumbDown, "Dislike", { vm.react(item.id, -1) }, ACTION_SIZE, tint = if (reaction == -1) accent else muted)
                IconAction(if (speaking == item.id) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp, if (speaking == item.id) "Stop reading" else "Read aloud", { vm.speak(item.id, item.text) }, ACTION_SIZE, tint = if (speaking == item.id) accent else muted)
                IconAction(Icons.Rounded.Share, "Share", { vm.shareText(item.text) }, ACTION_SIZE)
                Box {
                    IconAction(Icons.Rounded.MoreVert, "More", { menu = true }, ACTION_SIZE)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; vm.copyText(item.text) })
                        DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; vm.shareText(item.text) })
                        DropdownMenuItem(text = { Text("Select text") }, onClick = { menu = false; select = true })
                        if (isLast && !running) DropdownMenuItem(text = { Text("Regenerate") }, onClick = { menu = false; vm.regenerate() })
                        firstCodeBlock(item.text)?.let { (lang, code) ->
                            DropdownMenuItem(text = { Text("Inspect code") }, onClick = { menu = false; vm.inspectCode(code, lang) })
                        }
                    }
                }
            }
        }
    }
    if (select) SelectTextDialog(item.text) { select = false }
}

/** The user's own message, with Copy, Share and ⋮ (Edit, Select text). */
@Composable
fun UserMessage(item: UiItem.User, vm: ChatViewModel, running: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var select by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        LongPressMenu(item.text, vm) { UserBubble(item.text) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Rounded.ContentCopy, "Copy", { vm.copyText(item.text) }, ACTION_SIZE)
            IconAction(Icons.Rounded.Share, "Share", { vm.shareText(item.text) }, ACTION_SIZE)
            Box {
                IconAction(Icons.Rounded.MoreVert, "More", { menu = true }, ACTION_SIZE)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; vm.copyText(item.text) })
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; vm.shareText(item.text) })
                    DropdownMenuItem(text = { Text("Select text") }, onClick = { menu = false; select = true })
                    if (!running) DropdownMenuItem(text = { Text("Edit and resend") }, onClick = { menu = false; vm.editUserMessage(item.id) })
                }
            }
        }
    }
    if (select) SelectTextDialog(item.text) { select = false }
}

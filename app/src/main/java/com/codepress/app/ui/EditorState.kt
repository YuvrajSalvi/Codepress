package com.codepress.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** One open file in the editor. */
class EditorTab(val path: String, saved: String, value: TextFieldValue) {
    var saved by mutableStateOf(saved)
    var value by mutableStateOf(value)
    val undo = ArrayList<TextFieldValue>()
    val redo = ArrayList<TextFieldValue>()
    private var lastPush = 0L

    val dirty: Boolean get() = value.text != saved
    val name: String get() = path.substringAfterLast('/')

    /** Applies a user edit and remembers the previous state for undo. */
    fun edit(new: TextFieldValue) {
        if (new.text != value.text) {
            val now = System.currentTimeMillis()
            if (undo.isEmpty() || now - lastPush > 700) {
                undo += value
                if (undo.size > 100) undo.removeAt(0)
                lastPush = now
            }
            redo.clear()
        }
        value = new
    }

    fun undo() {
        if (undo.isEmpty()) return
        redo += value
        value = undo.removeAt(undo.lastIndex)
        lastPush = 0
    }

    fun redo() {
        if (redo.isEmpty()) return
        undo += value
        value = redo.removeAt(redo.lastIndex)
        lastPush = 0
    }

    fun goToLine(line: Int) {
        val lines = value.text.split('\n')
        val n = (line - 1).coerceIn(0, lines.lastIndex)
        val offset = lines.take(n).sumOf { it.length + 1 }
        value = value.copy(selection = TextRange(offset))
    }
}

/** All open editor tabs of the current project. */
class EditorState {
    val tabs = mutableStateListOf<EditorTab>()
    var active by mutableIntStateOf(-1)

    val current: EditorTab? get() = tabs.getOrNull(active)

    fun clear() {
        tabs.clear()
        active = -1
    }

    fun indexOf(path: String) = tabs.indexOfFirst { it.path == path }

    fun open(path: String, text: String, line: Int = 0) {
        val i = indexOf(path)
        if (i >= 0) {
            active = i
        } else {
            tabs += EditorTab(path, text, TextFieldValue(text))
            active = tabs.lastIndex
        }
        if (line > 0) current?.goToLine(line)
    }

    fun close(index: Int) {
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        active = when {
            tabs.isEmpty() -> -1
            index < active -> active - 1
            active >= tabs.size -> tabs.lastIndex
            else -> active
        }
    }

    /** Replaces the text of open tabs whose file changed on disk, but only when the user has no unsaved edits there. */
    fun reloadClean(path: String, text: String) {
        val t = tabs.firstOrNull { it.path == path } ?: return
        if (t.dirty || t.saved == text) return
        t.saved = text
        t.value = TextFieldValue(text, TextRange(minOf(t.value.selection.start, text.length)))
    }

    fun renamed(old: String, new: String) {
        val i = indexOf(old)
        if (i < 0) return
        val t = tabs[i]
        tabs[i] = EditorTab(new, t.saved, t.value)
    }
}

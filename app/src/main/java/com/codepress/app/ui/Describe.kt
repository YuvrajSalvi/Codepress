package com.codepress.app.ui

import com.codepress.app.agent.ToolCall
import com.codepress.app.agent.str

fun toolTarget(c: ToolCall): String = when (c.name) {
    "run_command" -> c.arguments.str("command") ?: ""
    "search_files" -> c.arguments.str("pattern") ?: ""
    else -> c.arguments.str("path") ?: ""
}

private fun short(s: String, n: Int = 70) = if (s.length > n) s.take(n) + "…" else s

/** What the agent is doing right now, in plain words. */
fun runningLabel(c: ToolCall): String {
    val t = short(toolTarget(c).replace('\n', ' '))
    return when (c.name) {
        "list_dir" -> "Listing ${t.ifEmpty { "." }}"
        "read_file" -> "Reading $t"
        "write_file" -> "Writing $t"
        "edit_file" -> "Editing $t"
        "search_files" -> "Searching for $t"
        "run_command" -> "Running $t"
        else -> c.name
    }
}

/** What the agent did, once finished. */
fun doneLabel(c: ToolCall): String {
    val t = short(toolTarget(c).replace('\n', ' '))
    return when (c.name) {
        "list_dir" -> "Listed ${t.ifEmpty { "." }}"
        "read_file" -> "Read $t"
        "write_file" -> "Wrote $t"
        "edit_file" -> "Edited $t"
        "search_files" -> "Searched for $t"
        "run_command" -> "Ran $t"
        else -> c.name
    }
}

fun formatDuration(ms: Long): String =
    if (ms < 60_000) String.format("%.1fs", ms / 1000.0) else "${ms / 60_000}m ${(ms % 60_000) / 1000}s"

fun lastLines(text: String, n: Int): String = text.lines().takeLast(n).joinToString("\n")

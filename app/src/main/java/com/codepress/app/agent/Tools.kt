package com.codepress.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

private fun objectSchema(
    required: List<String>,
    props: List<Triple<String, String, String>>, // name, type, description
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        for ((name, type, desc) in props) {
            putJsonObject(name) {
                put("type", type)
                put("description", desc)
            }
        }
    }
    putJsonArray("required") { required.forEach { add(it) } }
}

class ToolExecutor(private val workspace: Workspace) {

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "list_dir",
            "List the files and folders in a directory of the workspace.",
            objectSchema(emptyList(), listOf(Triple("path", "string", "Directory, relative to the workspace. Default: \".\""))),
        ),
        ToolSpec(
            "read_file",
            "Read a text file. Returns numbered lines. Use start_line/end_line for large files.",
            objectSchema(
                listOf("path"),
                listOf(
                    Triple("path", "string", "File path, relative to the workspace."),
                    Triple("start_line", "integer", "First line to return (1-based). Optional."),
                    Triple("end_line", "integer", "Last line to return (inclusive). Optional."),
                ),
            ),
        ),
        ToolSpec(
            "write_file",
            "Create a file, or completely overwrite an existing one. Parent folders are created automatically.",
            objectSchema(
                listOf("path", "content"),
                listOf(
                    Triple("path", "string", "File path, relative to the workspace."),
                    Triple("content", "string", "The full new content of the file."),
                ),
            ),
        ),
        ToolSpec(
            "edit_file",
            "Replace one exact piece of text in an existing file. old_text must match exactly once; include enough surrounding lines to make it unique.",
            objectSchema(
                listOf("path", "old_text", "new_text"),
                listOf(
                    Triple("path", "string", "File path, relative to the workspace."),
                    Triple("old_text", "string", "The exact text to replace."),
                    Triple("new_text", "string", "The replacement text."),
                ),
            ),
        ),
        ToolSpec(
            "search_files",
            "Search text files for a regular expression. Returns path:line: text for each match.",
            objectSchema(
                listOf("pattern"),
                listOf(
                    Triple("pattern", "string", "Regular expression to look for."),
                    Triple("path", "string", "Directory to search, relative to the workspace. Default: \".\""),
                ),
            ),
        ),
        ToolSpec(
            "run_command",
            "Run a shell command (Android sh) with the workspace as the working directory. Only basic Android/toybox tools are available (ls, cat, grep, sed, mkdir, cp, mv, rm, echo, ...).",
            objectSchema(
                listOf("command"),
                listOf(
                    Triple("command", "string", "The shell command to run."),
                    Triple("timeout_seconds", "integer", "Kill the command after this many seconds. Default 60, max 300."),
                ),
            ),
        ),
    )

    val names: Set<String> = specs.map { it.name }.toSet()

    /** Tools that change things (files or processes) ask the user first, unless auto-approve is on. */
    fun needsApproval(toolName: String): Boolean =
        toolName == "write_file" || toolName == "edit_file" || toolName == "run_command"

    suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        try {
            ToolResult(call.id, call.name, truncate(dispatch(call)), false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(call.id, call.name, "Error: ${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    private suspend fun dispatch(call: ToolCall): String {
        val a = call.arguments
        return when (call.name) {
            "list_dir" -> listDir(a.str("path") ?: ".")
            "read_file" -> readFile(need(a, "path"), a.int("start_line"), a.int("end_line"))
            "write_file" -> writeFile(need(a, "path"), a.str("content") ?: error("Missing argument: content"))
            "edit_file" -> editFile(need(a, "path"), need(a, "old_text"), a.str("new_text") ?: error("Missing argument: new_text"))
            "search_files" -> searchFiles(need(a, "pattern"), a.str("path") ?: ".")
            "run_command" -> runCommand(need(a, "command"), a.int("timeout_seconds") ?: 60)
            else -> error("Unknown tool: ${call.name}")
        }
    }

    private fun need(a: JsonObject, key: String): String =
        a.str(key)?.takeIf { it.isNotEmpty() } ?: error("Missing argument: $key")

    private fun listDir(path: String): String {
        val dir = workspace.resolve(path)
        require(dir.exists()) { "No such directory: $path" }
        require(dir.isDirectory) { "Not a directory: $path" }
        val entries = dir.listFiles().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (entries.isEmpty()) return "(empty directory)"
        val sb = StringBuilder()
        for (e in entries.take(500)) {
            if (e.isDirectory) sb.append(e.name).append("/\n") else sb.append(e.name).append("  (").append(e.length()).append(" bytes)\n")
        }
        if (entries.size > 500) sb.append("... and ${entries.size - 500} more")
        return sb.toString().trimEnd()
    }

    private fun readFile(path: String, startLine: Int?, endLine: Int?): String {
        val file = workspace.resolve(path)
        require(file.exists()) { "No such file: $path" }
        require(file.isFile) { "Not a file: $path" }
        require(file.length() <= MAX_READ_BYTES) { "File is too large (${file.length()} bytes). Use search_files or run_command (e.g. sed -n '1,100p')." }
        if (looksBinary(file)) return "(binary file, ${file.length()} bytes)"
        val lines = file.readLines()
        if (lines.isEmpty()) return "(empty file)"
        val from = (startLine ?: 1).coerceAtLeast(1)
        if (from > lines.size) return "File has only ${lines.size} lines."
        val to = (endLine ?: (from + MAX_READ_LINES - 1)).coerceAtMost(lines.size).coerceAtMost(from + MAX_READ_LINES - 1)
        val sb = StringBuilder()
        for (i in from..to) sb.append(i.toString().padStart(5)).append('\t').append(lines[i - 1]).append('\n')
        if (to < lines.size) sb.append("... (${lines.size} lines total; showing $from-$to)")
        return sb.toString().trimEnd('\n')
    }

    private fun writeFile(path: String, content: String): String {
        val file = workspace.resolve(path)
        require(!file.isDirectory) { "Path is a directory: $path" }
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Wrote ${content.toByteArray().size} bytes to ${workspace.relative(file)}"
    }

    private fun editFile(path: String, oldText: String, newText: String): String {
        val file = workspace.resolve(path)
        require(file.isFile) { "No such file: $path" }
        val text = file.readText()
        var count = 0
        var idx = text.indexOf(oldText)
        while (idx >= 0) {
            count++
            idx = text.indexOf(oldText, idx + oldText.length)
        }
        require(count != 0) { "old_text was not found in $path. Read the file again and copy the text exactly (whitespace matters)." }
        require(count == 1) { "old_text matches $count places in $path. Add more surrounding lines so it matches exactly once." }
        file.writeText(text.replace(oldText, newText))
        return "Edited ${workspace.relative(file)} (1 replacement)"
    }

    private fun searchFiles(pattern: String, path: String): String {
        val dir = workspace.resolve(path)
        require(dir.exists()) { "No such path: $path" }
        val regex = try {
            Regex(pattern)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid regular expression: ${e.message}")
        }
        val out = StringBuilder()
        var matches = 0
        val files = if (dir.isFile) sequenceOf(dir) else dir.walkTopDown().filter { it.isFile }
        for (f in files) {
            if (matches >= MAX_MATCHES) break
            if (f.length() > MAX_READ_BYTES || looksBinary(f)) continue
            val rel = workspace.relative(f)
            f.useLines { lines ->
                for ((i, line) in lines.withIndex()) {
                    if (regex.containsMatchIn(line)) {
                        out.append(rel).append(':').append(i + 1).append(": ").append(line.take(300)).append('\n')
                        matches++
                        if (matches >= MAX_MATCHES) break
                    }
                }
            }
        }
        if (matches == 0) return "No matches."
        if (matches >= MAX_MATCHES) out.append("... (stopped after $MAX_MATCHES matches)")
        return out.toString().trimEnd()
    }

    private suspend fun runCommand(command: String, timeoutSeconds: Int): String {
        val timeout = timeoutSeconds.coerceIn(1, 300)
        val shell = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
        val pb = ProcessBuilder(shell, "-c", command)
            .directory(workspace.root)
            .redirectErrorStream(true)
        pb.environment()["HOME"] = workspace.root.path
        val process = pb.start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(output) { if (output.length < MAX_OUTPUT_CHARS * 2) output.append(buf, 0, n) }
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout.toLong())
        var timedOut = false
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() > deadline) {
                    timedOut = true
                    break
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        reader.join(1000)
        val text = synchronized(output) { output.toString() }.trimEnd()
        if (timedOut) return "$text\n[killed: timed out after ${timeout}s]".trim()
        val code = process.exitValue()
        return if (text.isEmpty()) "[exit code $code]" else "$text\n[exit code $code]"
    }

    private fun looksBinary(file: File): Boolean =
        file.inputStream().use { s ->
            val buf = ByteArray(4000)
            val n = s.read(buf)
            (0 until maxOf(n, 0)).any { buf[it].toInt() == 0 }
        }

    companion object {
        const val MAX_OUTPUT_CHARS = 20_000
        private const val MAX_READ_BYTES = 1_000_000L
        private const val MAX_READ_LINES = 2000
        private const val MAX_MATCHES = 100

        fun truncate(text: String, max: Int = MAX_OUTPUT_CHARS): String =
            if (text.length <= max) text
            else text.take(max * 3 / 4) + "\n... [truncated ${text.length - max} characters] ...\n" + text.takeLast(max / 4)
    }
}

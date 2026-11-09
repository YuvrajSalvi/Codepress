package com.codepress.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

enum class ShellOrigin { USER, AI }
enum class ShellState { RUNNING, COMPLETED, FAILED, STOPPED }

data class ShellEntry(
    val id: Long,
    val tag: String?,
    val command: String,
    val origin: ShellOrigin,
    val cwd: String,
    val state: ShellState,
    val output: String,
    val exitCode: Int?,
    val durationMs: Long,
)

/**
 * One shell shared by the user (Shell tab) and the AI agent (run_command tool).
 * Every command is recorded as an entry with live output, state, exit code and duration.
 * User commands run in the current directory (changed with `cd`, kept inside the workspace);
 * AI commands always start in the workspace root.
 */
class ShellRunner(private val workspace: Workspace) {

    @Volatile
    private var cwd: File = workspace.root
    private val counter = AtomicLong(0)
    private val processes = ConcurrentHashMap<Long, Process>()
    private val stopped = ConcurrentHashMap.newKeySet<Long>()

    private val _entries = MutableStateFlow<List<ShellEntry>>(emptyList())
    val entries: StateFlow<List<ShellEntry>> = _entries

    private val _cwdLabel = MutableStateFlow("~")
    val cwdLabel: StateFlow<String> = _cwdLabel

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history

    fun lastId(): Long = counter.get()

    private fun label(dir: File): String =
        "~" + if (dir.path == workspace.root.path) "" else "/" + workspace.relative(dir)

    /** Set by the app: opens a workspace file in the built-in editor (used by the nano / vi / edit commands). */
    var openEditor: ((String) -> Unit)? = null

    suspend fun run(command: String, origin: ShellOrigin, timeoutSec: Int = 60, tag: String? = null, onStart: (Long) -> Unit = {}, workDir: String? = null): ShellEntry =
        withContext(Dispatchers.IO) {
            val id = counter.incrementAndGet()
            onStart(id)
            val dir = if (workDir != null) workspace.resolve(workDir.ifEmpty { "." }) else if (origin == ShellOrigin.AI) workspace.root else cwd
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            _entries.update { (it + ShellEntry(id, tag, command, origin, label(dir), ShellState.RUNNING, "", null, 0)).takeLast(MAX_ENTRIES) }
            if (origin == ShellOrigin.USER) {
                _history.update { h -> (listOf(command) + h.filter { it != command }).take(50) }
            }

            val trimmed = command.trim()
            CommandGuard.check(command, dir, workspace.root)?.let {
                return@withContext finish(id, ShellState.FAILED, it, 126, elapsed())
            }
            builtin(trimmed, dir, origin)?.let { (code, text) ->
                return@withContext finish(id, if (code == 0) ShellState.COMPLETED else ShellState.FAILED, text, code, elapsed())
            }
            if (origin == ShellOrigin.USER && (trimmed == "cd" || trimmed.startsWith("cd "))) {
                val arg = trimmed.removePrefix("cd").trim().trim('"', '\'')
                val target = when {
                    arg.isEmpty() || arg == "~" -> workspace.root
                    arg.startsWith("~/") -> File(workspace.root, arg.removePrefix("~/"))
                    arg.startsWith("/") -> File(workspace.root, arg.trimStart('/'))
                    else -> File(dir, arg)
                }.canonicalFile
                val inside = workspace.contains(target)
                val ok = inside && target.isDirectory
                if (ok) {
                    cwd = target
                    _cwdLabel.value = label(target)
                }
                val msg = when {
                    !inside -> "cd: outside the workspace is not allowed"
                    !target.isDirectory -> "cd: no such directory: $arg"
                    else -> ""
                }
                return@withContext finish(id, if (ok) ShellState.COMPLETED else ShellState.FAILED, msg, if (ok) 0 else 1, elapsed())
            }

            val timeout = timeoutSec.coerceIn(1, 3600)
            val shell = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
            val pb = ProcessBuilder(shell, "-c", command).directory(dir).redirectErrorStream(true)
            pb.environment()["HOME"] = workspace.root.path
            val process = try {
                pb.start()
            } catch (e: Exception) {
                return@withContext finish(id, ShellState.FAILED, "Could not start the command: ${e.message}", 127, elapsed())
            }
            processes[id] = process
            process.outputStream.close()

            val out = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            synchronized(out) { if (out.length < MAX_COLLECT) out.append(buf, 0, n) }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            reader.isDaemon = true
            reader.start()

            var timedOut = false
            var cancelled = false
            var lastLen = -1
            try {
                while (!process.waitFor(150, TimeUnit.MILLISECONDS)) {
                    currentCoroutineContext().ensureActive()
                    val snap = snapshot(out)
                    if (snap.length != lastLen) {
                        lastLen = snap.length
                        val ms = elapsed()
                        _entries.update { l -> l.map { if (it.id == id) it.copy(output = snap, durationMs = ms) else it } }
                    }
                    if (elapsed() > timeout * 1000L) {
                        timedOut = true
                        break
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                if (process.isAlive) process.destroyForcibly()
                processes.remove(id)
                if (cancelled) {
                    reader.join(500)
                    finish(id, ShellState.STOPPED, (snapshot(out) + "\n[stopped]").trim(), null, elapsed())
                }
            }
            reader.join(1000)
            val wasStopped = stopped.remove(id)
            val text = snapshot(out) + when {
                timedOut -> "\n[killed: timed out after ${timeout}s]"
                wasStopped -> "\n[stopped]"
                else -> ""
            }
            val code = if (timedOut || wasStopped) null else process.exitValue()
            val state = when {
                wasStopped -> ShellState.STOPPED
                timedOut -> ShellState.FAILED
                code == 0 -> ShellState.COMPLETED
                else -> ShellState.FAILED
            }
            finish(id, state, text.trim(), code, elapsed())
        }

    private fun splitArgs(cmd: String): List<String>? {
        if (cmd.any { it in "|&;<>$`" }) return null
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (c in cmd) {
            when {
                quote != null -> if (c == quote) quote = null else cur.append(c)
                c == '"' || c == '\'' -> quote = c
                c.isWhitespace() -> if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** unzip / zip / nano that work without any installed tools. Returns null for everything else. */
    private fun builtin(cmd: String, dir: File, origin: ShellOrigin): Pair<Int, String>? {
        val parts = splitArgs(cmd) ?: return null
        if (parts.isEmpty()) return null
        val ops = FileOps(workspace)
        fun rel(p: String): String {
            val f = (if (p.startsWith("/")) File(workspace.root, p.trimStart('/')) else File(dir, p)).canonicalFile
            require(workspace.contains(f)) { "outside the project folder" }
            return workspace.relative(f).let { if (it == ".") "" else it }
        }
        return try {
            when (parts[0]) {
                "unzip" -> {
                    val args = parts.drop(1)
                    var dest: String? = null
                    val names = mutableListOf<String>()
                    var i = 0
                    while (i < args.size) {
                        when {
                            args[i] == "-d" && i + 1 < args.size -> { dest = args[i + 1]; i++ }
                            args[i].startsWith("-") -> {}
                            else -> names += args[i]
                        }
                        i++
                    }
                    val zip = names.firstOrNull() ?: return 1 to "usage: unzip [-d dir] file.zip"
                    val zipRel = rel(zip)
                    val destRel = if (dest != null) rel(dest) else workspace.relative(dir).let { if (it == ".") "" else it }
                    val n = ops.unzip(zipRel, destRel)
                    0 to "Extracted $n file${if (n == 1) "" else "s"} from ${zip.substringAfterLast('/')}" + (if (dest != null) " into $dest" else "")
                }
                "zip" -> {
                    val args = parts.drop(1).filter { !it.startsWith("-") }
                    if (args.size < 2) return 1 to "usage: zip [-r] out.zip file-or-folder..."
                    val n = ops.zipToFile(rel(args[0]), args.drop(1).map { rel(it) })
                    0 to "Created ${args[0]} with $n entr${if (n == 1) "y" else "ies"}"
                }
                "nano", "vi", "vim", "edit" -> {
                    val file = parts.getOrNull(1) ?: return 1 to "usage: ${parts[0]} file"
                    if (origin == ShellOrigin.AI) return 1 to "${parts[0]} is interactive and cannot be used by the agent. Use write_file or edit_file instead."
                    val r = rel(file)
                    val f = workspace.resolve(r)
                    if (!f.exists()) { f.parentFile?.mkdirs(); f.createNewFile() }
                    openEditor?.invoke(r)
                    0 to "Opened ${file} in the CodePress editor (Editor tab)."
                }
                else -> null
            }
        } catch (e: Exception) {
            1 to "${parts[0]}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Stops a running command (user or AI). */
    fun stop(id: Long) {
        stopped.add(id)
        processes[id]?.destroyForcibly()
    }

    /** Removes finished entries from the screen. Running commands stay. */
    fun clear() {
        _entries.update { l -> l.filter { it.state == ShellState.RUNNING } }
    }

    fun deleteHistory() {
        _history.value = emptyList()
    }

    private fun finish(id: Long, state: ShellState, text: String, code: Int?, ms: Long): ShellEntry {
        var result: ShellEntry? = null
        _entries.update { list ->
            list.map {
                if (it.id == id) it.copy(state = state, output = text, exitCode = code, durationMs = ms).also { r -> result = r } else it
            }
        }
        return result ?: ShellEntry(id, null, "", ShellOrigin.AI, "", state, text, code, ms)
    }

    private fun snapshot(out: StringBuilder): String = synchronized(out) {
        if (out.length <= MAX_SHOWN) out.toString()
        else out.substring(0, 15_000) + "\n... [output truncated] ...\n" + out.substring(out.length - (MAX_SHOWN - 15_000))
    }

    private companion object {
        const val MAX_ENTRIES = 200
        const val MAX_COLLECT = 400_000
        const val MAX_SHOWN = 60_000
    }
}

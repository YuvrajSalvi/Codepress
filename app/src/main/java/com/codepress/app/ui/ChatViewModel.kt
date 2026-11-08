package com.codepress.app.ui

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arm.aichat.AiChat
import com.codepress.app.agent.AgentListener
import com.codepress.app.agent.AgentLoop
import com.codepress.app.agent.ChatMessage
import com.codepress.app.agent.ShellOrigin
import com.codepress.app.agent.ShellRunner
import com.codepress.app.agent.SystemPrompt
import com.codepress.app.agent.ToolCall
import com.codepress.app.agent.ToolExecutor
import com.codepress.app.agent.ToolResult
import com.codepress.app.agent.Workspace
import com.codepress.app.data.LocalModels
import com.codepress.app.data.Notifier
import com.codepress.app.data.Project
import com.codepress.app.data.ProjectStore
import com.codepress.app.data.ProviderType
import com.codepress.app.data.SecureSettings
import com.codepress.app.data.WorkspaceTransfer
import com.codepress.app.data.humanSize
import com.codepress.app.llm.AnthropicProvider
import com.codepress.app.llm.GeminiProvider
import com.codepress.app.llm.LlmProvider
import com.codepress.app.llm.LocalProvider
import com.codepress.app.llm.OpenAiProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

enum class ToolStatus { QUEUED, AWAITING, RUNNING, DONE, FAILED, DENIED }

sealed interface UiItem {
    val id: Long

    data class User(override val id: Long, val text: String) : UiItem
    data class Assistant(override val id: Long, val text: String, val streaming: Boolean) : UiItem
    data class Tool(
        override val id: Long,
        val call: ToolCall,
        val status: ToolStatus,
        val output: String?,
        val startedAt: Long = 0,
        val durationMs: Long = 0,
        val shellFrom: Long = 0,
    ) : UiItem
    data class Notice(override val id: Long, val text: String, val isError: Boolean) : UiItem

    /** Files the AI created or changed during one tool call. Shown in chat with Download / Preview. */
    data class Files(override val id: Long, val created: List<String>, val updated: List<String>) : UiItem
}

enum class StatusKind { THINKING, WRITING, WORKING, WAITING }

data class AgentStatus(val kind: StatusKind, val label: String, val since: Long)

private val FILE_TOOLS = setOf("write_file", "edit_file", "run_command")

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SecureSettings(app)
    val projects = ProjectStore(app)
    val notifier = Notifier(app)
    val models = LocalModels(app)

    // The workspace and shell belong to the open project; they are swapped when a project is opened.
    var workspace by mutableStateOf(Workspace(File(app.filesDir, "projects/_none/workspace")))
        private set
    var shell by mutableStateOf(ShellRunner(workspace))
        private set
    private var tools = ToolExecutor(workspace, shell)
    private var transfer = WorkspaceTransfer(app, workspace)

    private val localProvider = LocalProvider(
        engineFactory = { AiChat.getInferenceEngine(getApplication<Application>()) },
        modelPath = { settings.localModelName.takeIf { it.isNotBlank() }?.let { models.file(it)?.absolutePath } },
    )

    private val _projectList = MutableStateFlow<List<Project>>(emptyList())
    val projectList: StateFlow<List<Project>> = _projectList

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project

    private val _items = MutableStateFlow<List<UiItem>>(emptyList())
    val items: StateFlow<List<UiItem>> = _items

    private val _status = MutableStateFlow<AgentStatus?>(null)
    val status: StateFlow<AgentStatus?> = _status

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** Bumped whenever settings, models or workspace files change, so screens refresh. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private val _modelStatus = MutableStateFlow<String?>(null)
    val modelStatus: StateFlow<String?> = _modelStatus
    private val _modelProgress = MutableStateFlow<Float?>(null)
    val modelProgress: StateFlow<Float?> = _modelProgress
    private val _modelBusy = MutableStateFlow(false)
    val modelBusy: StateFlow<Boolean> = _modelBusy

    private val _fileStatus = MutableStateFlow<String?>(null)
    val fileStatus: StateFlow<String?> = _fileStatus

    /** Red badge numbers: files the AI touched / commands the AI ran that the user has not looked at yet. */
    private val _unseenFiles = MutableStateFlow(0)
    val unseenFiles: StateFlow<Int> = _unseenFiles
    private val _shellSeen = MutableStateFlow(0L)
    val shellSeen: StateFlow<Long> = _shellSeen

    private val _preview = MutableStateFlow<PreviewTarget?>(null)
    val preview: StateFlow<PreviewTarget?> = _preview

    private val history = mutableListOf<ChatMessage>()
    private var job: Job? = null
    private var modelJob: Job? = null
    private var nextId = 1L

    @Volatile
    private var streamingId: Long? = null

    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null
    private val callToItem = ConcurrentHashMap<String, Long>()
    private val fileSnap = ConcurrentHashMap<String, Map<String, Long>>()

    // ------------------------------------------------- app in foreground?

    @Volatile
    private var startedActivities = 0
    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { startedActivities++ }
        override fun onActivityStopped(activity: Activity) { startedActivities-- }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun shouldNotify(): Boolean =
        settings.notificationsOn && (!settings.notifyOnlyBackground || startedActivities <= 0)

    init {
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        projects.migrateLegacy()
        refreshProjects()
    }

    override fun onCleared() {
        getApplication<Application>().unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        super.onCleared()
    }

    fun bump() = _revision.update { it + 1 }

    fun activeLabel(): String = when (settings.provider) {
        ProviderType.ANTHROPIC -> "Anthropic · ${settings.model(ProviderType.ANTHROPIC)}"
        ProviderType.GEMINI -> "Gemini · ${settings.model(ProviderType.GEMINI).ifBlank { "no model" }}"
        ProviderType.OPENAI -> "OpenAI-compatible · ${settings.model(ProviderType.OPENAI).ifBlank { "no model" }}"
        ProviderType.LOCAL -> "On-device · ${settings.localModelName.ifBlank { "no model" }}"
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------ projects

    fun refreshProjects() {
        _projectList.value = projects.list()
    }

    fun createProject(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val p = projects.create(n)
        refreshProjects()
        openProject(p)
    }

    fun renameProject(id: String, name: String) {
        if (name.isBlank()) return
        projects.rename(id, name)
        refreshProjects()
        _project.value?.takeIf { it.id == id }?.let { _project.value = it.copy(name = name.trim().take(40)) }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            projects.delete(id)
            refreshProjects()
        }
    }

    fun openProject(p: Project) = switchProject(p)

    fun closeProject() = switchProject(null)

    private fun switchProject(target: Project?) {
        viewModelScope.launch {
            stopAndWait()
            persistNow()
            resetChatState()
            if (target == null) {
                _project.value = null
                refreshProjects()
                return@launch
            }
            val ws = Workspace(projects.workspaceDir(target.id))
            val sh = ShellRunner(ws)
            workspace = ws
            shell = sh
            tools = ToolExecutor(ws, sh)
            transfer = WorkspaceTransfer(getApplication(), ws)
            val loaded = withContext(Dispatchers.IO) {
                projects.loadChat(target.id)?.let { ChatPersistence.decode(it) }
            }
            if (loaded != null) {
                history.addAll(loaded.history)
                _items.value = loaded.items
                nextId = (loaded.items.maxOfOrNull { it.id } ?: 0L) + 1
            }
            _shellSeen.value = 0
            _project.value = target
            bump()
        }
    }

    private suspend fun stopAndWait() {
        val j = job
        if (j != null && j.isActive) {
            pending?.complete(false)
            j.cancelAndJoin()
        }
    }

    private fun resetChatState() {
        history.clear()
        _items.value = emptyList()
        streamingId = null
        callToItem.clear()
        fileSnap.clear()
        nextId = 1L
        _unseenFiles.value = 0
        localProvider.reset()
    }

    private fun snapshotChat(): Triple<String, List<UiItem>, List<ChatMessage>>? {
        val p = _project.value ?: return null
        return Triple(p.id, _items.value.takeLast(400), history.toList())
    }

    private fun persist() {
        val (id, itemsSnap, hist) = snapshotChat() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { projects.saveChat(id, ChatPersistence.encode(itemsSnap, hist)) }
        }
    }

    private suspend fun persistNow() {
        val (id, itemsSnap, hist) = snapshotChat() ?: return
        withContext(Dispatchers.IO) {
            runCatching { projects.saveChat(id, ChatPersistence.encode(itemsSnap, hist)) }
        }
    }

    // ---------------------------------------------------------------- chat

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || _running.value || _project.value == null) return
        val problem = settings.configProblem()
        if (problem != null) {
            add(UiItem.Notice(newId(), problem, true))
            return
        }
        add(UiItem.User(newId(), message))
        history += ChatMessage.User(message)
        _running.value = true
        setStatus(StatusKind.THINKING, "Thinking")
        val runStartId = nextId
        persist()
        job = viewModelScope.launch {
            var cancelled = false
            try {
                AgentLoop(buildProvider(), tools, settings.autoApprove, systemPrompt = { SystemPrompt.build(workspace) }).run(history, listener)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Throwable) {
                // Nothing answered the last user message; forget it so a retry does not duplicate it.
                if (history.lastOrNull() is ChatMessage.User) history.removeAt(history.lastIndex)
                add(UiItem.Notice(newId(), friendlyError(e), true))
            } finally {
                finishRun(cancelled, runStartId)
            }
        }
    }

    fun stop() {
        job?.cancel()
        pending?.complete(false)
    }

    /** Wipes the open project's conversation (its files stay). */
    fun clearChat() {
        viewModelScope.launch {
            stopAndWait()
            resetChatState()
            persistNow()
        }
    }

    fun answerApproval(allow: Boolean) {
        pending?.complete(allow)
    }

    /** Switch between the models that already have a key / file set up. */
    fun switchModel(provider: ProviderType) {
        if (_running.value) return
        settings.provider = provider
        localProvider.reset()
        bump()
    }

    private fun buildProvider(): LlmProvider = when (settings.provider) {
        ProviderType.ANTHROPIC -> AnthropicProvider(
            apiKey = settings.apiKey(ProviderType.ANTHROPIC),
            model = settings.model(ProviderType.ANTHROPIC),
        )
        ProviderType.GEMINI -> GeminiProvider(
            apiKey = settings.apiKey(ProviderType.GEMINI),
            model = settings.model(ProviderType.GEMINI),
        )
        ProviderType.OPENAI -> OpenAiProvider(
            baseUrl = settings.openAiBaseUrl,
            apiKey = settings.apiKey(ProviderType.OPENAI),
            model = settings.model(ProviderType.OPENAI),
        )
        ProviderType.LOCAL -> localProvider
    }

    private fun finishRun(cancelled: Boolean, runStartId: Long) {
        _status.value = null
        streamingId = null
        pending = null
        _items.update { list ->
            list.map {
                when {
                    it is UiItem.Assistant && it.streaming -> it.copy(streaming = false)
                    it is UiItem.Tool && (it.status == ToolStatus.AWAITING || it.status == ToolStatus.RUNNING || it.status == ToolStatus.QUEUED) ->
                        it.copy(status = ToolStatus.FAILED, output = it.output ?: "Cancelled")
                    else -> it
                }
            }
        }
        _running.value = false
        bump() // the agent may have changed files
        persist()
        if (!cancelled && shouldNotify()) {
            val reply = _items.value.lastOrNull { it is UiItem.Assistant && it.id >= runStartId } as? UiItem.Assistant
            if (reply != null && reply.text.isNotBlank()) {
                notifier.aiReplied(_project.value?.name ?: "CodePress", reply.text.replace(Regex("[*#`]"), "").trim())
            }
        }
    }

    private val listener = object : AgentListener {
        override fun onAssistantText(text: String) {
            val sid = streamingId
            if (sid == null) {
                val id = newId()
                streamingId = id
                setStatus(StatusKind.WRITING, "Writing response")
                add(UiItem.Assistant(id, text, true))
            } else {
                replace(sid) { (it as UiItem.Assistant).copy(text = text) }
            }
        }

        override fun onAssistantDone(text: String, calls: List<ToolCall>) {
            val sid = streamingId
            streamingId = null
            if (sid != null) {
                if (text.isBlank()) remove(sid) else replace(sid) { (it as UiItem.Assistant).copy(text = text, streaming = false) }
            } else if (text.isNotBlank()) {
                add(UiItem.Assistant(newId(), text, false))
            }
            for (c in calls) {
                val id = newId()
                callToItem[c.id] = id
                add(UiItem.Tool(id, c, ToolStatus.QUEUED, null))
            }
        }

        override suspend fun requestApproval(call: ToolCall): Boolean {
            val d = CompletableDeferred<Boolean>()
            pending = d
            setStatus(StatusKind.WAITING, "Waiting for your approval")
            setTool(call, ToolStatus.AWAITING, null)
            if (shouldNotify()) notifier.needsApproval(_project.value?.name ?: "CodePress", runningLabel(call))
            try {
                return d.await()
            } finally {
                if (pending === d) pending = null
            }
        }

        override fun onToolRunning(call: ToolCall) {
            if (call.name in FILE_TOOLS) snapshotFiles()?.let { fileSnap[call.id] = it }
            setStatus(StatusKind.WORKING, runningLabel(call))
            setTool(call, ToolStatus.RUNNING, null)
        }

        override fun onToolResult(call: ToolCall, result: ToolResult, denied: Boolean) {
            val status = when {
                denied -> ToolStatus.DENIED
                result.isError -> ToolStatus.FAILED
                else -> ToolStatus.DONE
            }
            setTool(call, status, result.output)
            fileSnap.remove(call.id)?.let { reportFileChanges(it) }
            setStatus(StatusKind.THINKING, "Thinking")
        }

        override fun onNotice(message: String) {
            add(UiItem.Notice(newId(), message, false))
        }
    }

    private fun setTool(call: ToolCall, status: ToolStatus, output: String?) {
        val id = callToItem[call.id] ?: return
        val now = System.currentTimeMillis()
        replace(id) {
            val t = it as UiItem.Tool
            when (status) {
                ToolStatus.RUNNING -> t.copy(status = status, startedAt = now, shellFrom = shell.lastId())
                ToolStatus.DONE, ToolStatus.FAILED, ToolStatus.DENIED ->
                    t.copy(status = status, output = output ?: t.output, durationMs = if (t.startedAt > 0) now - t.startedAt else 0)
                else -> t.copy(status = status, output = output ?: t.output)
            }
        }
    }

    private fun setStatus(kind: StatusKind, label: String) {
        _status.value = AgentStatus(kind, label, System.currentTimeMillis())
    }

    // ---------------------------------------------- AI file change tracking

    /** path -> last modified time, or null when the workspace is too big to track cheaply. */
    private fun snapshotFiles(): Map<String, Long>? = try {
        val ws = workspace
        val list = ws.listFiles()
        if (list.size > 3000) null else list.associateWith { runCatching { ws.resolve(it).lastModified() }.getOrDefault(0L) }
    } catch (e: Throwable) {
        null
    }

    private fun reportFileChanges(before: Map<String, Long>) {
        val after = snapshotFiles() ?: return
        fun noise(p: String) = p.startsWith(".git/") || "/node_modules/" in "/$p" || "__pycache__" in p
        val created = after.keys.filter { it !in before && !noise(it) }.sorted()
        val updated = after.keys.filter { before[it] != null && before[it] != after[it] && !noise(it) }.sorted()
        if (created.isEmpty() && updated.isEmpty()) return
        add(UiItem.Files(newId(), created, updated))
        val count = created.size + updated.size
        _unseenFiles.update { it + count }
        bump()
        if (shouldNotify()) notifier.filesChanged(_project.value?.name ?: "CodePress", count)
    }

    fun markFilesSeen() {
        _unseenFiles.value = 0
    }

    fun markShellSeen(id: Long) {
        if (id > _shellSeen.value) _shellSeen.value = id
    }

    // ------------------------------------------------- download and preview

    fun exportFile(path: String, uri: Uri) {
        val ws = workspace
        viewModelScope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    val src = ws.resolve(path)
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "w")
                        ?: throw IOException("Cannot open the destination")
                    out.use { o -> src.inputStream().use { it.copyTo(o) } }
                }
                "Saved ${path.substringAfterLast('/')}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "Download failed: ${friendlyError(e)}"
            }
            _fileStatus.value = msg
            toast(msg)
        }
    }

    fun previewFile(path: String) {
        val ext = path.substringAfterLast('.', "").lowercase()
        when (ext) {
            "html", "htm", "svg" -> _preview.value = PreviewTarget(workspace.resolve(path), path.substringAfterLast('/'))
            "php" -> toast("PHP needs a PHP runtime, which is planned for Phase 2. HTML and SVG can be previewed now.")
            else -> toast("This file type cannot be previewed. Use Download instead.")
        }
    }

    fun previewCode(code: String, language: String) {
        when (val l = language.lowercase()) {
            "php" -> toast("PHP needs a PHP runtime, which is planned for Phase 2. HTML and SVG can be previewed now.")
            else -> {
                val html = if (l == "svg") "<!doctype html><html><body style=\"margin:0;background:#fff\">$code</body></html>" else code
                val dir = File(getApplication<Application>().cacheDir, "preview").apply { mkdirs() }
                val f = File(dir, "index.html")
                f.writeText(html)
                _preview.value = PreviewTarget(f, "Preview")
            }
        }
    }

    fun closePreview() {
        _preview.value = null
    }

    // -------------------------------------------------------------- shell

    /** Runs a command typed by the user (or sent from a code block's Run button) in the shared shell. */
    fun runInShell(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        viewModelScope.launch {
            try {
                shell.run(cmd, ShellOrigin.USER, timeoutSec = 3600)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                add(UiItem.Notice(newId(), "Shell error: ${friendlyError(e)}", true))
            }
            bump() // commands may have changed files
        }
    }

    fun stopShell(id: Long) = shell.stop(id)

    fun clearShell() = shell.clear()

    @Synchronized
    private fun newId(): Long = nextId++

    private fun add(item: UiItem) = _items.update { it + item }

    private fun remove(id: Long) = _items.update { list -> list.filterNot { it.id == id } }

    private fun replace(id: Long, transform: (UiItem) -> UiItem) =
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }

    // ------------------------------------------------------- local models

    private var modelVerb = "Copying"
    private var progLastT = 0L
    private var progLastBytes = 0L
    private var progSpeed = 0.0

    fun importModel(uri: Uri) = launchModelTask("Copying") { models.importFrom(uri, ::onModelProgress) }

    fun downloadModel(url: String) {
        if (url.isBlank()) {
            _modelStatus.value = "Paste a direct link to a .gguf file first."
            return
        }
        launchModelTask("Downloading") { models.download(url.trim(), ::onModelProgress) }
    }

    fun cancelModelTask() {
        modelJob?.cancel()
    }

    fun selectModel(name: String) {
        settings.localModelName = name
        localProvider.reset()
        bump()
    }

    fun deleteModel(name: String) {
        models.delete(name)
        if (settings.localModelName == name) settings.localModelName = ""
        localProvider.reset()
        bump()
    }

    private fun eta(seconds: Long) = if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"

    private fun onModelProgress(done: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        if (progLastT == 0L) {
            progLastT = now
            progLastBytes = done
        }
        val dt = now - progLastT
        if (dt >= 700) {
            val instant = (done - progLastBytes) * 1000.0 / dt
            progSpeed = if (progSpeed == 0.0) instant else progSpeed * 0.6 + instant * 0.4
            progLastT = now
            progLastBytes = done
        }
        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
        _modelProgress.value = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        val speed = if (progSpeed > 0) " · ${humanSize(progSpeed.toLong())}/s" else ""
        val left = if (total > 0 && progSpeed > 0) " · ${eta(((total - done) / progSpeed).toLong())} left" else ""
        val text = humanSize(done) + (if (total > 0) " / ${humanSize(total)} · $pct%" else "") + speed + left
        _modelStatus.value = "$modelVerb… $text"
        if (settings.notificationsOn) notifier.progress("$modelVerb model", text, pct)
    }

    private fun launchModelTask(verb: String, block: suspend () -> String) {
        if (modelJob?.isActive == true) return
        modelVerb = verb
        progLastT = 0L
        progLastBytes = 0L
        progSpeed = 0.0
        modelJob = viewModelScope.launch {
            _modelBusy.value = true
            _modelProgress.value = null
            _modelStatus.value = "Starting…"
            try {
                val name = block()
                settings.localModelName = name
                localProvider.reset()
                _modelStatus.value = "Ready: $name"
                if (settings.notificationsOn) notifier.progressDone("Model ready", name)
            } catch (e: CancellationException) {
                _modelStatus.value = "Cancelled."
                notifier.cancelProgress()
                throw e
            } catch (e: Throwable) {
                _modelStatus.value = "Failed: ${friendlyError(e)}"
                if (settings.notificationsOn) notifier.progressDone("$verb failed", friendlyError(e))
            } finally {
                _modelBusy.value = false
                _modelProgress.value = null
                bump()
            }
        }
    }

    // ---------------------------------------------------------- workspace

    fun importFolder(uri: Uri) = fileTask("Importing folder…") { "Imported ${transfer.importFolder(uri)} files." }
    fun importFiles(uris: List<Uri>) = fileTask("Importing files…") { "Imported ${transfer.importFiles(uris)} files." }
    fun exportZip(uri: Uri) = fileTask("Exporting…") { "Exported ${transfer.exportZip(uri)} files to a zip." }

    fun clearWorkspace() {
        workspace.clear()
        _fileStatus.value = "Workspace cleared."
        bump()
    }

    private fun fileTask(start: String, block: suspend () -> String) {
        viewModelScope.launch {
            _fileStatus.value = start
            _fileStatus.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "Failed: ${friendlyError(e)}"
            }
            bump()
        }
    }
}

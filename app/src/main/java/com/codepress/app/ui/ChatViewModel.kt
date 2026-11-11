package com.codepress.app.ui

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.FileProvider
import com.codepress.app.agent.FileOps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import com.codepress.app.data.AgentService
import com.codepress.app.data.ApkBuilder
import com.codepress.app.data.Notifier
import com.codepress.app.data.Project
import com.codepress.app.data.ProjectStore
import com.codepress.app.data.ProviderType
import com.codepress.app.data.SecureSettings
import com.codepress.app.data.LinuxEnv
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
    data class Files(override val id: Long, val created: List<String>, val updated: List<String>, val deleted: List<String> = emptyList()) : UiItem
}

enum class StatusKind { THINKING, WRITING, WORKING, WAITING }

data class AgentStatus(val kind: StatusKind, val label: String, val since: Long)

private val FILE_TOOLS = setOf("write_file", "edit_file", "run_command", "build_apk")

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SecureSettings(app)
    val auth = com.codepress.app.data.AuthManager()
    val linuxEnv = LinuxEnv(app).also { env -> env.gitCredentials = { settings.githubUser to settings.githubToken } }
    val projects = ProjectStore(app)
    val notifier = Notifier(app)
    val models = LocalModels(app)

    // The workspace and shell belong to the open project; they are swapped when a project is opened.
    var workspace by mutableStateOf(Workspace(File(app.filesDir, "projects/_none/workspace")))
        private set
    var shell by mutableStateOf(ShellRunner(workspace, linuxEnv) { settings.useLinuxForAiCommands })
        private set
    private var tools = newTools(workspace, shell)
    private var transfer = WorkspaceTransfer(app, workspace)

    /** Tools for the AI. build_apk uses the GitHub account from Settings → Plugins. */
    private fun newTools(ws: Workspace, sh: ShellRunner) = ToolExecutor(
        ws,
        sh,
        apkBuilder = { status -> ApkBuilder(settings.githubUser, settings.githubToken).build(ws, status) },
        onStatus = { setStatus(StatusKind.WORKING, "Building APK: $it") },
    )

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

    // ---- editor, reactions, read aloud, always-allow (added with the editor update)
    val editor = EditorState()
    private val uiPrefs = app.getSharedPreferences("codepress_ui", Context.MODE_PRIVATE)
    val fileOps: FileOps get() = FileOps(workspace)

    private val _editorRequest = MutableStateFlow(0)
    val editorRequest: StateFlow<Int> = _editorRequest
    private val _filesRequest = MutableStateFlow(0)
    val filesRequest: StateFlow<Int> = _filesRequest
    private val _chatRequest = MutableStateFlow(0)
    val chatRequest: StateFlow<Int> = _chatRequest
    private val _filesFocus = MutableStateFlow<String?>(null)
    val filesFocus: StateFlow<String?> = _filesFocus
    private val _inspect = MutableStateFlow<PreviewTarget?>(null)
    val inspect: StateFlow<PreviewTarget?> = _inspect
    private val _runtimeMissing = MutableStateFlow<Pair<String, List<String>>?>(null)
    val runtimeMissing: StateFlow<Pair<String, List<String>>?> = _runtimeMissing
    private val _editorRunId = MutableStateFlow<Long?>(null)
    val editorRunId: StateFlow<Long?> = _editorRunId
    private val _draft = MutableStateFlow<String?>(null)
    val draft: StateFlow<String?> = _draft
    private val _reactions = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val reactions: StateFlow<Map<Long, Int>> = _reactions
    private val _speakingId = MutableStateFlow<Long?>(null)
    val speakingId: StateFlow<Long?> = _speakingId
    private val _allowAlways = MutableStateFlow(false)
    val allowAlwaysFlow: StateFlow<Boolean> = _allowAlways
    private var tts: TextToSpeech? = null
    private var ttsReady = false

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
        projects.migrateFolderNames()
        refreshProjects()
    }

    override fun onCleared() {
        tts?.shutdown()
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
            val sh = ShellRunner(ws, linuxEnv) { settings.useLinuxForAiCommands }
            workspace = ws
            shell = sh
            sh.openEditor = { path -> openInEditor(path) }
            tools = newTools(ws, sh)
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
            loadReactions(target.id)
            _allowAlways.value = uiPrefs.getBoolean("allow_${target.id}", false)
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
        editor.clear()
        _runtimeMissing.value = null
        _editorRunId.value = null
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
        launchRun()
    }

    /** Runs the agent on the current history (which must end with a user message). */
    private fun launchRun() {
        _running.value = true
        setStatus(StatusKind.THINKING, "Thinking")
        AgentService.start(getApplication(), _project.value?.name ?: "CodePress")
        val runStartId = nextId
        val local = settings.provider == ProviderType.LOCAL
        persist()
        job = viewModelScope.launch {
            var cancelled = false
            try {
                AgentLoop(
                    buildProvider(),
                    tools,
                    settings.autoApprove,
                    systemPrompt = { SystemPrompt.build(workspace, local, settings.useLinuxForAiCommands && linuxEnv.isInstalled && linuxEnv.nativeBinariesPresent, if (settings.githubToken.isNotBlank()) settings.githubUser else "") },
                    isAutoApproved = { true },
                ).run(history, listener)
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
        AgentService.stop(getApplication())
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

    /** "path" -> last modified for files, "path/" -> 0 for folders. Null when the project is too big to track cheaply. */
    private fun snapshotFiles(): Map<String, Long>? {
        return try {
            val ws = workspace
            val out = HashMap<String, Long>()
            for (f in ws.root.walkTopDown().onEnter { it.name != "node_modules" && it.name != ".git" && it.name != "__pycache__" }) {
                if (f == ws.root) continue
                if (out.size > 4000) return null
                val rel = ws.relative(f)
                out[if (f.isDirectory) "$rel/" else rel] = if (f.isFile) f.lastModified() else 0L
            }
            out
        } catch (e: Throwable) {
            null
        }
    }

    private fun reportFileChanges(before: Map<String, Long>) {
        val after = snapshotFiles() ?: return
        fun noise(p: String) = p.startsWith(".git/") || "/node_modules/" in "/$p" || "__pycache__" in p
        val created = after.keys.filter { it !in before && !noise(it) }.sorted()
        val updated = after.keys.filter { !it.endsWith("/") && before[it] != null && before[it] != after[it] && !noise(it) }.sorted()
        val deletedAll = before.keys.filter { it !in after && !noise(it) }.sorted()
        // A deleted folder also lists everything that was inside it; keep only the folder itself.
        val deleted = deletedAll.filter { d -> deletedAll.none { o -> o != d && o.endsWith("/") && d.startsWith(o) } }
        if (created.isEmpty() && updated.isEmpty() && deleted.isEmpty()) return
        add(UiItem.Files(newId(), created, updated, deleted))
        val count = created.size + updated.size + deleted.size
        _unseenFiles.update { it + count }
        bump()
        // Open editor tabs without unsaved edits follow the file on disk.
        val ws = workspace
        viewModelScope.launch {
            for (path in updated) {
                if (editor.indexOf(path) < 0) continue
                val text = withContext(Dispatchers.IO) { runCatching { ws.resolve(path).readText() }.getOrNull() } ?: continue
                editor.reloadClean(path, text)
            }
        }
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
                    if (src.isDirectory) out.use { o -> FileOps(ws).zipTo(listOf(path), o) } else out.use { o -> src.inputStream().use { it.copyTo(o) } }
                }
                "Saved ${path.substringAfterLast('/')}${if (ws.resolve(path).isDirectory) ".zip" else ""}"
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
            "html", "htm", "svg" -> _preview.value = PreviewTarget(workspace.resolve(path), path.substringAfterLast('/'), path)
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

    // ======================================================= folders, ZIP and files

    private fun toastShort(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    private fun fsTask(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            _fileStatus.value = label
            val msg = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "Failed: ${friendlyError(e)}"
            }
            _fileStatus.value = msg
            bump()
            if (msg.startsWith("Failed")) toast(msg)
        }
    }

    fun fsCreateFile(dir: String, name: String, open: Boolean = false) {
        val ws = workspace
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    val n = name.trim().trimStart('/')
                    val parent = listOf(dir, n.substringBeforeLast('/', "")).filter { it.isNotEmpty() }.joinToString("/")
                    ws.resolve(parent.ifEmpty { "." }).mkdirs()
                    FileOps(ws).createFile(parent, n.substringAfterLast('/'))
                }
                _fileStatus.value = "Created $path"
                bump()
                if (open) openInEditor(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't create the file: ${friendlyError(e)}")
            }
        }
    }

    fun fsMkdir(parent: String, name: String) {
        val ws = workspace
        fsTask("Creating folder…") {
            val n = name.trim().trim('/')
            val full = listOf(parent, n).filter { it.isNotEmpty() }.joinToString("/")
            val d = ws.resolve(full)
            require(!d.exists()) { "\"$n\" already exists" }
            require(d.mkdirs()) { "Could not create the folder" }
            "Created folder $full"
        }
    }

    fun fsRename(path: String, newName: String) {
        val ops = fileOps
        viewModelScope.launch {
            try {
                val np = withContext(Dispatchers.IO) { ops.rename(path, newName) }
                editor.renamed(path, np)
                _fileStatus.value = "Renamed to ${np.substringAfterLast('/')}"
                bump()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't rename: ${friendlyError(e)}")
            }
        }
    }

    fun fsDelete(paths: List<String>) {
        val ops = fileOps
        viewModelScope.launch {
            try {
                val n = withContext(Dispatchers.IO) { ops.delete(paths) }
                for (i in editor.tabs.indices.reversed()) {
                    val tp = editor.tabs[i].path
                    if (paths.any { tp == it || tp.startsWith("$it/") }) editor.close(i)
                }
                _fileStatus.value = "Deleted $n file${if (n == 1) "" else "s"}"
                bump()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't delete: ${friendlyError(e)}")
            }
        }
    }

    fun fsCopy(paths: List<String>, dest: String) {
        val ops = fileOps
        fsTask("Copying…") { val n = ops.copy(paths, dest); "Copied $n item${if (n == 1) "" else "s"} to ${dest.ifEmpty { "project root" }}" }
    }

    fun fsMove(paths: List<String>, dest: String) {
        val ops = fileOps
        fsTask("Moving…") { val n = ops.move(paths, dest); "Moved $n item${if (n == 1) "" else "s"} to ${dest.ifEmpty { "project root" }}" }
    }

    fun fsExtract(zip: String, dest: String, deleteZip: Boolean) {
        val ops = fileOps
        val ws = workspace
        fsTask("Extracting…") {
            val n = ops.unzip(zip, dest)
            if (deleteZip) ws.resolve(zip).delete()
            "Extracted $n file${if (n == 1) "" else "s"} to ${dest.ifEmpty { "project root" }}"
        }
    }

    fun importFilesInto(uris: List<Uri>, dir: String) {
        val ws = workspace
        val tr = transfer
        fileTask("Importing…") { "Imported ${tr.importFiles(uris, ws.resolve(dir.ifEmpty { "." }).also { it.mkdirs() })} file(s) into ${dir.ifEmpty { "the project" }}." }
    }

    fun exportPaths(paths: List<String>, uri: Uri) {
        val ws = workspace
        fsTask("Exporting…") {
            val out = getApplication<Application>().contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open the destination")
            val single = paths.singleOrNull()?.let { ws.resolve(it) }
            if (single != null && single.isFile) {
                out.use { o -> single.inputStream().use { it.copyTo(o) } }
                "Saved ${single.name}"
            } else {
                val n = out.use { o -> FileOps(ws).zipTo(paths, o) }
                "Saved a ZIP with $n entries"
            }
        }
    }

    /** Shares files through the Android share sheet (copied to a temporary folder and handed out with a FileProvider). */
    fun sharePaths(paths: List<String>) {
        val ws = workspace
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val shared = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
                    val single = paths.singleOrNull()?.let { ws.resolve(it) }
                    if (single != null && single.isFile) single.copyTo(File(dir, single.name), overwrite = true)
                    else File(dir, (single?.name ?: "files") + ".zip").also { f -> f.outputStream().use { FileOps(ws).zipTo(paths, it) } }
                }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", shared)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = if (shared.extension == "zip") "application/zip" else mimeFor(shared.name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't share these files: ${friendlyError(e)}")
            }
        }
    }

    /** Opens the Android installer for an APK in the workspace (Android asks once to allow installs from CodePress). */
    fun installApk(path: String) {
        val ws = workspace
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    val src = ws.resolve(path)
                    val dir = File(app.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
                    src.copyTo(File(dir, src.name), overwrite = true)
                }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", apk)
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(install)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't open the installer: ${friendlyError(e)}")
            }
        }
    }

    /** Creates a new project from a ZIP that is in the current project. */
    fun importZipAsProject(zipPath: String) {
        val ws = workspace
        fsTask("Creating project from ZIP…") {
            val src = ws.resolve(zipPath)
            require(src.isFile) { "No such file" }
            val p = projects.create(src.nameWithoutExtension.take(40).ifEmpty { "Imported project" })
            val n = FileOps(ws).unzipExternal(src, projects.workspaceDir(p.id))
            withContext(Dispatchers.Main) { refreshProjects() }
            "Created project \"${p.name}\" with $n files. Open it from your projects list."
        }
    }

    fun openInFiles(path: String) {
        _preview.value = null
        _inspect.value = null
        _filesFocus.value = path
        _filesRequest.update { it + 1 }
    }

    fun consumeFilesFocus() {
        _filesFocus.value = null
    }

    // ------------------------------------------------------------- editor

    fun openInEditor(path: String, line: Int = 0) {
        val ws = workspace
        viewModelScope.launch {
            val idx = editor.indexOf(path)
            if (idx >= 0) {
                editor.active = idx
                if (line > 0) editor.current?.goToLine(line)
            } else {
                val text = try {
                    withContext(Dispatchers.IO) {
                        val f = ws.resolve(path)
                        require(f.isFile) { "not a file" }
                        require(f.length() <= 1_500_000) { "too large for the editor (${humanSize(f.length())}); use Download" }
                        val bytes = f.readBytes()
                        require(bytes.take(4000).none { it.toInt() == 0 }) { "this looks like a binary file" }
                        String(bytes, Charsets.UTF_8)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    toast("Couldn't open ${path.substringAfterLast('/')}: ${friendlyError(e)}")
                    return@launch
                }
                editor.open(path, text, line)
            }
            _editorRequest.update { it + 1 }
        }
    }

    fun saveTab(tab: EditorTab) {
        val ws = workspace
        val text = tab.value.text
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ws.resolve(tab.path).writeText(text) }
                tab.saved = text
                _fileStatus.value = "Saved ${tab.name}"
                bump()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("File access is unavailable. Couldn't save ${tab.name}: ${friendlyError(e)}")
            }
        }
    }

    private suspend fun saveAllTabs() {
        val ws = workspace
        for (t in editor.tabs.toList()) {
            if (!t.dirty) continue
            val text = t.value.text
            try {
                withContext(Dispatchers.IO) { ws.resolve(t.path).writeText(text) }
                t.saved = text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't save ${t.name}: ${friendlyError(e)}")
            }
        }
        bump()
    }

    fun saveTabAs(tab: EditorTab, name: String) {
        val ws = workspace
        val text = tab.value.text
        val target = name.trim().trimStart('/')
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val f = ws.resolve(target)
                    require(!f.isDirectory) { "that is a folder" }
                    f.parentFile?.mkdirs()
                    f.writeText(text)
                }
                bump()
                editor.open(target, text)
                _fileStatus.value = "Saved as $target"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't save as $target: ${friendlyError(e)}")
            }
        }
    }

    fun formatJson(tab: EditorTab) {
        try {
            val el = Json.parseToJsonElement(tab.value.text)
            val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(JsonElement.serializer(), el)
            tab.edit(TextFieldValue(pretty))
        } catch (e: Throwable) {
            toast("Couldn't format: this is not valid JSON")
        }
    }

    fun askAiAbout(prompt: String) {
        viewModelScope.launch {
            saveAllTabs()
            _chatRequest.update { it + 1 }
            send(prompt)
        }
    }

    fun askAiToFix(command: String, output: String) {
        _chatRequest.update { it + 1 }
        send("This command failed:\n```\n$ $command\n${output.takeLast(3000)}\n```\nFind the cause and fix it, then run it again to check.")
    }

    private fun hasProgram(name: String): Boolean = try {
        ProcessBuilder("/system/bin/sh", "-c", "command -v $name").redirectErrorStream(true).start().waitFor() == 0
    } catch (e: Exception) {
        false
    }

    fun runFile(tab: EditorTab) {
        val path = tab.path
        val ext = path.substringAfterLast('.', "").lowercase()
        viewModelScope.launch {
            saveAllTabs()
            if (ext == "html" || ext == "htm" || ext == "svg") {
                previewFile(path)
                return@launch
            }
            val spec = runSpecFor(path)
            if (spec == null) {
                toast("This file type can't be run directly. Ask the AI to run it, or use the Shell.")
                return@launch
            }
            val missing = withContext(Dispatchers.IO) { spec.needs.filter { !hasProgram(it) } }
            val blocked = if (spec.anyOf) missing.size == spec.needs.size else missing.isNotEmpty()
            if (blocked) {
                _runtimeMissing.value = spec.label to (if (spec.anyOf) spec.needs else missing)
                return@launch
            }
            val sh = shell
            try {
                sh.run(
                    spec.command(path.substringAfterLast('/')),
                    ShellOrigin.USER,
                    timeoutSec = 3600,
                    onStart = { id -> _editorRunId.value = id },
                    workDir = path.substringBeforeLast('/', ""),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't run: ${friendlyError(e)}")
            }
            bump()
        }
    }

    fun clearRuntimeMissing() {
        _runtimeMissing.value = null
    }

    fun clearEditorRun() {
        _editorRunId.value = null
    }

    // ----------------------------------------------------- message actions

    fun copyText(text: String, done: String = "Copied") {
        try {
            val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CodePress", text))
            toastShort(done)
        } catch (e: Throwable) {
            toast("Couldn't copy message")
        }
    }

    fun copyFileText(file: File, done: String) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() }
            if (text == null) toast("Couldn't copy the code") else copyText(text, done)
        }
    }

    fun shareText(text: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            getApplication<Application>().startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) {
            toast("Couldn't share this message")
        }
    }

    fun speak(id: Long, text: String) {
        if (_speakingId.value == id) {
            tts?.stop()
            _speakingId.value = null
            return
        }
        val clean = text.replace(Regex("```[\\s\\S]*?```"), " code block. ").replace(Regex("[*#`_>]"), "").trim()
        if (clean.isEmpty()) return
        fun go() {
            val t = tts ?: return
            _speakingId.value = id
            t.speak(clean.take(3900), TextToSpeech.QUEUE_FLUSH, null, "msg-$id")
        }
        if (tts == null) {
            tts = TextToSpeech(getApplication()) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { _speakingId.value = null }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { _speakingId.value = null }
                    })
                    go()
                } else {
                    tts = null
                    toast("Read aloud isn't available on this device")
                }
            }
        } else if (ttsReady) {
            go()
        }
    }

    private fun loadReactions(projectId: String) {
        _reactions.value = (uiPrefs.getString("react_$projectId", "") ?: "").split(',').mapNotNull {
            val p = it.split(':')
            val id = p.getOrNull(0)?.toLongOrNull()
            val v = p.getOrNull(1)?.toIntOrNull()
            if (id != null && v != null) id to v else null
        }.toMap()
    }

    fun react(id: Long, value: Int) {
        val p = _project.value ?: return
        val cur = _reactions.value
        val next = if (cur[id] == value) cur - id else cur + (id to value)
        _reactions.value = next
        uiPrefs.edit().putString("react_${p.id}", next.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
    }

    fun regenerate() {
        if (_running.value || _project.value == null) return
        val lastUser = history.indexOfLast { it is ChatMessage.User }
        if (lastUser < 0) return
        while (history.size > lastUser + 1) history.removeAt(history.lastIndex)
        val items = _items.value
        val lastUserItem = items.indexOfLast { it is UiItem.User }
        if (lastUserItem >= 0) _items.value = items.take(lastUserItem + 1)
        callToItem.clear()
        launchRun()
    }

    fun editUserMessage(itemId: Long) {
        if (_running.value) return
        val users = _items.value.filterIsInstance<UiItem.User>()
        val k = users.indexOfFirst { it.id == itemId }
        if (k < 0) return
        val text = users[k].text
        val same = users.drop(k + 1).count { it.text == text }
        val histIdx = history.withIndex().filter { (it.value as? ChatMessage.User)?.text == text }.map { it.index }
        histIdx.getOrNull(histIdx.size - 1 - same)?.let { hi -> while (history.size > hi) history.removeAt(history.lastIndex) }
        _items.value = _items.value.takeWhile { it.id != itemId }
        callToItem.clear()
        _draft.value = text
        persist()
    }

    fun consumeDraft() {
        _draft.value = null
    }

    fun inspectCode(code: String, language: String) {
        try {
            val ext = when (language.lowercase()) {
                "python", "py" -> "py"; "javascript", "js" -> "js"; "typescript", "ts" -> "ts"; "kotlin", "kt" -> "kt"
                "bash", "sh", "shell", "zsh" -> "sh"; "c++", "cpp" -> "cpp"; "rust", "rs" -> "rs"; "yaml", "yml" -> "yml"
                "markdown", "md" -> "md"; "" -> "txt"; else -> language.lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "txt" }
            }
            val dir = File(getApplication<Application>().cacheDir, "inspect").apply { mkdirs() }
            val f = File(dir, "response.$ext").apply { writeText(code) }
            _inspect.value = PreviewTarget(f, "AI code (${language.ifEmpty { "text" }})")
        } catch (e: Throwable) {
            toast("Couldn't open code inspector")
        }
    }

    fun closeInspect() {
        _inspect.value = null
    }

    // ------------------------------------------------------ always allow

    fun allowAlways() {
        val p = _project.value ?: return
        uiPrefs.edit().putBoolean("allow_${p.id}", true).apply()
        _allowAlways.value = true
        pending?.complete(true)
    }

    fun resetAlwaysAllow() {
        val p = _project.value ?: return
        uiPrefs.edit().putBoolean("allow_${p.id}", false).apply()
        _allowAlways.value = false
        toastShort("CodePress will ask before changing files again")
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

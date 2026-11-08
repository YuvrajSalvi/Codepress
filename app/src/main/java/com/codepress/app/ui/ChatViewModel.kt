package com.codepress.app.ui

import android.app.Application
import android.net.Uri
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
import com.codepress.app.data.ProviderType
import com.codepress.app.data.SecureSettings
import com.codepress.app.data.WorkspaceTransfer
import com.codepress.app.llm.AnthropicProvider
import com.codepress.app.llm.GeminiProvider
import com.codepress.app.llm.LlmProvider
import com.codepress.app.llm.LocalProvider
import com.codepress.app.llm.OpenAiProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
}

enum class StatusKind { THINKING, WRITING, WORKING, WAITING }

data class AgentStatus(val kind: StatusKind, val label: String, val since: Long)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SecureSettings(app)
    val workspace = Workspace(File(app.filesDir, "workspace"))
    val models = LocalModels(app)
    val shell = ShellRunner(workspace)
    private val tools = ToolExecutor(workspace, shell)
    private val transfer = WorkspaceTransfer(app, workspace)

    private val localProvider = LocalProvider(
        engineFactory = { AiChat.getInferenceEngine(getApplication<Application>()) },
        modelPath = { settings.localModelName.takeIf { it.isNotBlank() }?.let { models.file(it)?.absolutePath } },
    )

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

    private val history = mutableListOf<ChatMessage>()
    private var job: Job? = null
    private var modelJob: Job? = null
    private var nextId = 1L

    @Volatile
    private var streamingId: Long? = null

    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null
    private val callToItem = ConcurrentHashMap<String, Long>()

    fun bump() = _revision.update { it + 1 }

    fun activeLabel(): String = when (settings.provider) {
        ProviderType.ANTHROPIC -> "Anthropic · ${settings.model(ProviderType.ANTHROPIC)}"
        ProviderType.GEMINI -> "Gemini · ${settings.model(ProviderType.GEMINI).ifBlank { "no model" }}"
        ProviderType.OPENAI -> "OpenAI-compatible · ${settings.model(ProviderType.OPENAI).ifBlank { "no model" }}"
        ProviderType.LOCAL -> "On-device · ${settings.localModelName.ifBlank { "no model" }}"
    }

    // ---------------------------------------------------------------- chat

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || _running.value) return
        val problem = settings.configProblem()
        if (problem != null) {
            add(UiItem.Notice(newId(), problem, true))
            return
        }
        add(UiItem.User(newId(), message))
        history += ChatMessage.User(message)
        _running.value = true
        setStatus(StatusKind.THINKING, "Thinking")
        job = viewModelScope.launch {
            try {
                AgentLoop(buildProvider(), tools, settings.autoApprove, systemPrompt = { SystemPrompt.build(workspace) }).run(history, listener)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Nothing answered the last user message; forget it so a retry does not duplicate it.
                if (history.lastOrNull() is ChatMessage.User) history.removeAt(history.lastIndex)
                add(UiItem.Notice(newId(), friendlyError(e), true))
            } finally {
                finishRun()
            }
        }
    }

    fun stop() {
        job?.cancel()
        pending?.complete(false)
    }

    fun newChat() {
        job?.cancel()
        pending?.complete(false)
        history.clear()
        _items.value = emptyList()
        streamingId = null
        callToItem.clear()
        localProvider.reset()
    }

    fun answerApproval(allow: Boolean) {
        pending?.complete(allow)
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

    private fun finishRun() {
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
            try {
                return d.await()
            } finally {
                if (pending === d) pending = null
            }
        }

        override fun onToolRunning(call: ToolCall) {
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

    fun importModel(uri: Uri) = launchModelTask { models.importFrom(uri, ::onModelProgress) }

    fun downloadModel(url: String) {
        if (url.isBlank()) {
            _modelStatus.value = "Paste a direct link to a .gguf file first."
            return
        }
        launchModelTask { models.download(url.trim(), ::onModelProgress) }
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

    private fun onModelProgress(done: Long, total: Long) {
        _modelProgress.value = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        _modelStatus.value = "Copying… ${done / (1024 * 1024)} MB" + if (total > 0) " of ${total / (1024 * 1024)} MB" else ""
    }

    private fun launchModelTask(block: suspend () -> String) {
        if (modelJob?.isActive == true) return
        modelJob = viewModelScope.launch {
            _modelBusy.value = true
            _modelProgress.value = null
            _modelStatus.value = "Starting…"
            try {
                val name = block()
                settings.localModelName = name
                localProvider.reset()
                _modelStatus.value = "Ready: $name"
            } catch (e: CancellationException) {
                _modelStatus.value = "Cancelled."
                throw e
            } catch (e: Throwable) {
                _modelStatus.value = "Failed: ${friendlyError(e)}"
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

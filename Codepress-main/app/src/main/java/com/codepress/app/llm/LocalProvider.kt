package com.codepress.app.llm

import com.arm.aichat.InferenceEngine
import com.codepress.app.agent.AssistantTurn
import com.codepress.app.agent.ChatMessage
import com.codepress.app.agent.ToolProtocol
import com.codepress.app.agent.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Runs a GGUF model on the phone through llama.cpp.
 *
 * The native engine keeps the conversation (KV cache) itself, so this class only sends what is new
 * since the last call, and reloads the model when a fresh conversation starts.
 */
class LocalProvider(
    private val engineFactory: () -> InferenceEngine,
    private val modelPath: () -> String?,
) : LlmProvider {

    private var engine: InferenceEngine? = null
    private var loadedPath: String? = null
    private var needsReset = true
    private var consumed = 0 // how many history messages the native context already contains

    /** Call when the user starts a new chat. */
    fun reset() {
        needsReset = true
    }

    override suspend fun complete(
        history: List<ChatMessage>,
        system: String,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): AssistantTurn {
        val path = modelPath() ?: error("No local model selected. Import or download a .gguf model in Settings.")
        require(File(path).isFile) { "The selected model file is missing. Pick a model in Settings." }
        val eng = engine ?: engineFactory().also { engine = it }

        // Wait for the native library to finish initialising.
        withTimeout(60_000) {
            eng.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady || it is InferenceEngine.State.Error }
        }

        val fullSystem = system + "\n\n" + ToolProtocol.instructions(tools)
        val replay = needsReset || loadedPath != path || history.size < consumed
        val prompt: String
        if (replay) {
            withContext(Dispatchers.IO) {
                val s = eng.state.value
                if (s is InferenceEngine.State.ModelReady || s is InferenceEngine.State.Error) eng.cleanUp()
            }
            eng.loadModel(path)
            eng.setSystemPrompt(fullSystem)
            loadedPath = path
            needsReset = false
            consumed = 0
            prompt = transcript(history)
        } else {
            prompt = render(history.drop(consumed))
        }
        require(prompt.isNotBlank()) { "Nothing to send to the model." }

        val raw = StringBuilder()
        var finished = false
        try {
            eng.sendUserPrompt(prompt, PREDICT_LENGTH)
                .takeWhile { !finished }
                .collect { token ->
                    raw.append(token)
                    onText(ToolProtocol.visibleText(raw.toString()))
                    if (raw.contains(ToolProtocol.CLOSE)) finished = true
                }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            needsReset = true // the native context now holds a half finished answer
            throw e
        }
        // The loop appends the assistant message right after we return, so it counts as consumed too.
        consumed = history.size + 1

        val text = raw.toString()
        val calls = ToolProtocol.parse(text, tools.map { it.name }.toSet())
        return AssistantTurn(ToolProtocol.visibleText(text), calls)
    }

    /** Messages that are new for the native context, as one user turn. */
    private fun render(newMessages: List<ChatMessage>): String =
        newMessages.joinToString("\n\n") { m ->
            when (m) {
                is ChatMessage.User -> m.text
                is ChatMessage.Tool -> ToolProtocol.formatResults(m.results, TOOL_RESULT_CHARS)
                is ChatMessage.Assistant -> "" // already inside the native context
            }
        }.trim()

    /** Used after a reset: the earlier conversation as plain text, followed by the latest message. */
    private fun transcript(history: List<ChatMessage>): String {
        if (history.size <= 1) return render(history)
        val earlier = history.dropLast(1)
        val sb = StringBuilder("Earlier in this conversation:\n")
        for (m in earlier) {
            when (m) {
                is ChatMessage.User -> sb.append("User: ").append(m.text.take(800)).append('\n')
                is ChatMessage.Assistant -> sb.append("Assistant: ").append(m.text.take(800)).append('\n')
                is ChatMessage.Tool -> m.results.forEach { sb.append("Tool ${it.name} returned: ").append(it.output.take(300)).append('\n') }
            }
        }
        val tail = if (sb.length > TRANSCRIPT_CHARS) sb.takeLast(TRANSCRIPT_CHARS).toString() else sb.toString()
        return tail + "\nNow the latest message:\n" + render(listOf(history.last()))
    }

    companion object {
        private const val PREDICT_LENGTH = 1024
        private const val TOOL_RESULT_CHARS = 2500
        private const val TRANSCRIPT_CHARS = 5000
    }
}

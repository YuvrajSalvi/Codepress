package com.codepress.app.agent

import com.codepress.app.llm.LlmProvider

/** What the UI needs to know while the agent works. Implemented by the ViewModel. */
interface AgentListener {
    /** Visible text of the assistant message currently being streamed (full text so far). */
    fun onAssistantText(text: String)

    /** The assistant message is complete. [calls] are the tools it wants to run next. */
    fun onAssistantDone(text: String, calls: List<ToolCall>)

    /** Ask the user to allow a risky tool call. Return true to run it. */
    suspend fun requestApproval(call: ToolCall): Boolean

    fun onToolRunning(call: ToolCall)

    fun onToolResult(call: ToolCall, result: ToolResult, denied: Boolean)

    fun onNotice(message: String)
}

class AgentLoop(
    private val provider: LlmProvider,
    private val tools: ToolExecutor,
    private val autoApprove: Boolean,
    private val maxSteps: Int = 30,
) {
    /**
     * Runs the conversation until the model stops asking for tools.
     * [history] must already end with the new user message. It is extended in place.
     */
    suspend fun run(history: MutableList<ChatMessage>, listener: AgentListener) {
        var step = 0
        while (true) {
            step++
            if (step > maxSteps) {
                listener.onNotice("Stopped after $maxSteps steps. Send a message to let me continue.")
                return
            }

            val turn = provider.complete(history, SystemPrompt.base, tools.specs) { listener.onAssistantText(it) }
            history += ChatMessage.Assistant(turn.text, turn.toolCalls)
            listener.onAssistantDone(turn.text, turn.toolCalls)

            if (turn.toolCalls.isEmpty()) {
                if (turn.text.isBlank()) listener.onNotice("The model returned an empty answer. Try again or pick another model.")
                return
            }

            val results = mutableListOf<ToolResult>()
            try {
                for (call in turn.toolCalls) {
                    var denied = false
                    val result: ToolResult
                    val allowed = !tools.needsApproval(call.name) || autoApprove || listener.requestApproval(call)
                    if (!allowed) {
                        denied = true
                        result = ToolResult(call.id, call.name, "The user denied this action. Do not retry it; ask what to do instead.", true)
                    } else {
                        listener.onToolRunning(call)
                        result = tools.execute(call)
                    }
                    results += result
                    listener.onToolResult(call, result, denied)
                }
            } finally {
                // Providers require a result for every tool call, even when we were cancelled half way.
                val done = results.map { it.callId }.toSet()
                for (c in turn.toolCalls) {
                    if (c.id !in done) results += ToolResult(c.id, c.name, "Cancelled before it ran.", true)
                }
                history += ChatMessage.Tool(results.toList())
            }
        }
    }
}

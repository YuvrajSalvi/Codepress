package com.codepress.app.llm

import com.codepress.app.agent.AssistantTurn
import com.codepress.app.agent.ChatMessage
import com.codepress.app.agent.ToolSpec

interface LlmProvider {
    /**
     * Sends [history] to the model and returns its complete answer.
     * [onText] is called while the answer streams in, with the full visible text so far.
     */
    suspend fun complete(
        history: List<ChatMessage>,
        system: String,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): AssistantTurn
}

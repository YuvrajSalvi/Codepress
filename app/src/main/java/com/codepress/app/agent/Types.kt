package com.codepress.app.agent

import kotlinx.serialization.json.JsonObject

/** A tool the model may call. [schema] is a JSON Schema object describing the arguments. */
data class ToolSpec(val name: String, val description: String, val schema: JsonObject)

data class ToolCall(val id: String, val name: String, val arguments: JsonObject)

data class ToolResult(
    val callId: String,
    val name: String,
    val output: String,
    val isError: Boolean,
)

/** One complete answer from a model: visible text plus any tool calls it asked for. */
data class AssistantTurn(val text: String, val toolCalls: List<ToolCall>)

sealed interface ChatMessage {
    data class User(val text: String) : ChatMessage
    data class Assistant(val text: String, val toolCalls: List<ToolCall>) : ChatMessage
    data class Tool(val results: List<ToolResult>) : ChatMessage
}

package com.codepress.app.llm

import com.codepress.app.agent.AppJson
import com.codepress.app.agent.AssistantTurn
import com.codepress.app.agent.ChatMessage
import com.codepress.app.agent.ToolCall
import com.codepress.app.agent.ToolSpec
import com.codepress.app.agent.int
import com.codepress.app.agent.parseObject
import com.codepress.app.agent.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import java.io.IOException

class AnthropicProvider(
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = 8192,
    private val baseUrl: String = "https://api.anthropic.com",
) : LlmProvider {

    override suspend fun complete(
        history: List<ChatMessage>,
        system: String,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): AssistantTurn {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("stream", true)
            put("system", system)
            put("messages", toMessages(history))
            putJsonArray("tools") {
                for (t in tools) {
                    add(
                        buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("input_schema", t.schema)
                        },
                    )
                }
            }
        }
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(Http.jsonBody(body.toString()))
            .build()

        val acc = Accumulator(onText)
        Http.streamSse(request) { event, data -> acc.onEvent(event, data) }
        return acc.result()
    }

    /** Collects the streamed events of one Messages API response. */
    class Accumulator(private val onText: (String) -> Unit) {
        private class Block(val type: String, val id: String, val name: String) {
            val json = StringBuilder()
        }

        private val blocks = java.util.TreeMap<Int, Block>()
        private val text = StringBuilder()
        var stopReason: String? = null
            private set

        fun onEvent(event: String?, data: String) {
            val obj = parseObject(data) ?: return
            when (event ?: obj.str("type")) {
                "content_block_start" -> {
                    val index = obj.int("index") ?: return
                    val block = obj["content_block"] as? JsonObject ?: return
                    blocks[index] = Block(block.str("type") ?: "", block.str("id") ?: "", block.str("name") ?: "")
                }
                "content_block_delta" -> {
                    val index = obj.int("index") ?: return
                    val delta = obj["delta"] as? JsonObject ?: return
                    when (delta.str("type")) {
                        "text_delta" -> {
                            val t = delta.str("text").orEmpty()
                            if (t.isNotEmpty()) {
                                text.append(t)
                                onText(text.toString())
                            }
                        }
                        "input_json_delta" -> blocks[index]?.json?.append(delta.str("partial_json").orEmpty())
                    }
                }
                "message_delta" -> {
                    (obj["delta"] as? JsonObject)?.str("stop_reason")?.let { stopReason = it }
                }
                "error" -> {
                    val err = obj["error"] as? JsonObject
                    throw IOException("Anthropic error: ${err?.str("message") ?: data.take(300)}")
                }
            }
        }

        fun result(): AssistantTurn {
            val calls = blocks.values.filter { it.type == "tool_use" }.map {
                val args = if (it.json.isBlank()) JsonObject(emptyMap()) else parseObject(it.json.toString()) ?: JsonObject(emptyMap())
                ToolCall(it.id, it.name, args)
            }
            return AssistantTurn(text.toString().trim(), calls)
        }
    }

    companion object {
        /** Converts our neutral history to the Anthropic `messages` array. */
        fun toMessages(history: List<ChatMessage>): JsonArray = buildJsonArray {
            for (m in history) {
                when (m) {
                    is ChatMessage.User -> add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", m.text)
                        },
                    )
                    is ChatMessage.Assistant -> add(
                        buildJsonObject {
                            put("role", "assistant")
                            putJsonArray("content") {
                                if (m.text.isNotBlank()) {
                                    add(buildJsonObject { put("type", "text"); put("text", m.text) })
                                }
                                for (c in m.toolCalls) {
                                    add(
                                        buildJsonObject {
                                            put("type", "tool_use")
                                            put("id", c.id)
                                            put("name", c.name)
                                            put("input", c.arguments)
                                        },
                                    )
                                }
                                if (m.text.isBlank() && m.toolCalls.isEmpty()) {
                                    add(buildJsonObject { put("type", "text"); put("text", "(no answer)") })
                                }
                            }
                        },
                    )
                    is ChatMessage.Tool -> add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                for (r in m.results) {
                                    add(
                                        buildJsonObject {
                                            put("type", "tool_result")
                                            put("tool_use_id", r.callId)
                                            put("content", r.output.ifEmpty { "(no output)" })
                                            if (r.isError) put("is_error", true)
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

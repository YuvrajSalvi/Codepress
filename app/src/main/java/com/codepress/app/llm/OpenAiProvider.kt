package com.codepress.app.llm

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
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import java.io.IOException

/** Works with OpenAI and anything that speaks the same `/chat/completions` API (OpenRouter, Groq, Ollama, LM Studio, DeepSeek, Gemini...). */
class OpenAiProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : LlmProvider {

    override suspend fun complete(
        history: List<ChatMessage>,
        system: String,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): AssistantTurn {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("messages", toMessages(system, history))
            putJsonArray("tools") {
                for (t in tools) {
                    add(
                        buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.schema)
                            }
                        },
                    )
                }
            }
        }
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .post(Http.jsonBody(body.toString()))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        val acc = Accumulator(onText)
        Http.streamSse(builder.build()) { _, data -> acc.onData(data) }
        return acc.result()
    }

    /** Collects the streamed chunks of one chat completion. */
    class Accumulator(private val onText: (String) -> Unit) {
        private class Partial {
            var id: String = ""
            var name: String = ""
            val args = StringBuilder()
        }

        private val partials = java.util.TreeMap<Int, Partial>()
        private val text = StringBuilder()

        fun onData(data: String) {
            if (data.trim() == "[DONE]") return
            val obj = parseObject(data) ?: return
            (obj["error"] as? JsonObject)?.let {
                throw IOException("Provider error: ${it.str("message") ?: data.take(300)}")
            }
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
            val delta = choice["delta"] as? JsonObject ?: return
            delta.str("content")?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                onText(text.toString())
            }
            (delta["tool_calls"] as? JsonArray)?.forEachIndexed { pos, el ->
                val tc = el as? JsonObject ?: return@forEachIndexed
                val index = tc.int("index") ?: pos
                val p = partials.getOrPut(index) { Partial() }
                tc.str("id")?.takeIf { it.isNotEmpty() }?.let { p.id = it }
                (tc["function"] as? JsonObject)?.let { fn ->
                    fn.str("name")?.takeIf { it.isNotEmpty() }?.let { p.name = it }
                    fn.str("arguments")?.let { p.args.append(it) }
                }
            }
        }

        fun result(): AssistantTurn {
            val calls = partials.entries.filter { it.value.name.isNotEmpty() }.map { (index, p) ->
                val args = if (p.args.isBlank()) JsonObject(emptyMap()) else parseObject(p.args.toString()) ?: JsonObject(emptyMap())
                ToolCall(p.id.ifEmpty { "call_${index + 1}" }, p.name, args)
            }
            return AssistantTurn(text.toString().trim(), calls)
        }
    }

    companion object {
        fun toMessages(system: String, history: List<ChatMessage>): JsonArray = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            for (m in history) {
                when (m) {
                    is ChatMessage.User -> add(buildJsonObject { put("role", "user"); put("content", m.text) })
                    is ChatMessage.Assistant -> add(
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", m.text)
                            if (m.toolCalls.isNotEmpty()) {
                                putJsonArray("tool_calls") {
                                    for (c in m.toolCalls) {
                                        add(
                                            buildJsonObject {
                                                put("id", c.id)
                                                put("type", "function")
                                                putJsonObject("function") {
                                                    put("name", c.name)
                                                    put("arguments", c.arguments.toString())
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                    is ChatMessage.Tool -> for (r in m.results) {
                        add(
                            buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", r.callId)
                                put("content", r.output.ifEmpty { "(no output)" })
                            },
                        )
                    }
                }
            }
        }
    }
}

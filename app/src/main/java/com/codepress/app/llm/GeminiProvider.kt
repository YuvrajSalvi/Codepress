package com.codepress.app.llm

import com.codepress.app.agent.AssistantTurn
import com.codepress.app.agent.ChatMessage
import com.codepress.app.agent.ToolCall
import com.codepress.app.agent.ToolSpec
import com.codepress.app.agent.parseObject
import com.codepress.app.agent.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import java.io.IOException

/** Google Gemini through its native API. Only an API key and a model name are needed. */
class GeminiProvider(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) : LlmProvider {

    override suspend fun complete(
        history: List<ChatMessage>,
        system: String,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): AssistantTurn {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
            }
            put("contents", toContents(history))
            putJsonArray("tools") {
                add(
                    buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            for (t in tools) {
                                add(
                                    buildJsonObject {
                                        put("name", t.name)
                                        put("description", t.description)
                                        put("parameters", t.schema)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
        val name = model.removePrefix("models/")
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1beta/models/$name:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .post(Http.jsonBody(body.toString()))
            .build()
        val acc = Accumulator(onText)
        Http.streamSse(request) { _, data -> acc.onData(data) }
        return acc.result()
    }

    /** Collects the streamed chunks of one generateContent response. */
    class Accumulator(private val onText: (String) -> Unit) {
        private val text = StringBuilder()
        private val calls = mutableListOf<ToolCall>()

        fun onData(data: String) {
            val obj = parseObject(data) ?: return
            (obj["error"] as? JsonObject)?.let {
                throw IOException("Gemini error: ${it.str("message") ?: data.take(300)}")
            }
            (obj["promptFeedback"] as? JsonObject)?.str("blockReason")?.let {
                throw IOException("Gemini blocked the request ($it).")
            }
            val candidate = (obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
            val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return
            for (el in parts) {
                val part = el as? JsonObject ?: continue
                val fc = part["functionCall"] as? JsonObject
                if (fc != null) {
                    val name = fc.str("name") ?: continue
                    val args = fc["args"] as? JsonObject ?: JsonObject(emptyMap())
                    calls += ToolCall("gemini_${calls.size + 1}", name, args, part.str("thoughtSignature"))
                    continue
                }
                if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) continue
                part.str("text")?.takeIf { it.isNotEmpty() }?.let {
                    text.append(it)
                    onText(text.toString())
                }
            }
        }

        fun result(): AssistantTurn = AssistantTurn(text.toString().trim(), calls.toList())
    }

    companion object {
        fun toContents(history: List<ChatMessage>): JsonArray = buildJsonArray {
            for (m in history) {
                when (m) {
                    is ChatMessage.User -> add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") { add(buildJsonObject { put("text", m.text) }) }
                        },
                    )
                    is ChatMessage.Assistant -> add(
                        buildJsonObject {
                            put("role", "model")
                            putJsonArray("parts") {
                                if (m.text.isNotBlank()) add(buildJsonObject { put("text", m.text) })
                                for (c in m.toolCalls) {
                                    add(
                                        buildJsonObject {
                                            putJsonObject("functionCall") {
                                                put("name", c.name)
                                                put("args", c.arguments)
                                            }
                                            c.extra?.let { put("thoughtSignature", it) }
                                        },
                                    )
                                }
                                if (m.text.isBlank() && m.toolCalls.isEmpty()) add(buildJsonObject { put("text", "(no answer)") })
                            }
                        },
                    )
                    is ChatMessage.Tool -> add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                for (r in m.results) {
                                    add(
                                        buildJsonObject {
                                            putJsonObject("functionResponse") {
                                                put("name", r.name)
                                                putJsonObject("response") {
                                                    put(if (r.isError) "error" else "output", r.output.ifEmpty { "(no output)" })
                                                }
                                            }
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

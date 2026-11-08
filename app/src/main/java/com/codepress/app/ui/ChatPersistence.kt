package com.codepress.app.ui

import com.codepress.app.agent.ChatMessage
import com.codepress.app.agent.ToolCall
import com.codepress.app.agent.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Saves and restores one project's chat (what the screen shows + what the model remembers). */
object ChatPersistence {

    class Loaded(val items: List<UiItem>, val history: List<ChatMessage>)

    private const val MAX_OUTPUT = 30_000

    fun encode(items: List<UiItem>, history: List<ChatMessage>): String =
        buildJsonObject {
            put("v", 1)
            put("items", buildJsonArray { items.forEach { add(item(it)) } })
            put("history", buildJsonArray { history.forEach { add(message(it)) } })
        }.toString()

    fun decode(text: String): Loaded? = try {
        val root = Json.parseToJsonElement(text) as JsonObject
        val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> decodeItem(o) } }
        val history = (root["history"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> decodeMessage(o) } }.toMutableList()
        // A run that was cut off leaves a half finished turn at the end; drop it so the model is not confused.
        while (history.isNotEmpty()) {
            val last = history.last()
            if (last is ChatMessage.Assistant && last.toolCalls.isEmpty()) break
            history.removeAt(history.lastIndex)
        }
        Loaded(items, history)
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------ encode

    private fun call(c: ToolCall) = buildJsonObject {
        put("id", c.id)
        put("name", c.name)
        put("args", c.arguments)
        c.extra?.let { put("extra", it) }
    }

    private fun item(x: UiItem): JsonObject = when (x) {
        is UiItem.User -> buildJsonObject { put("k", "user"); put("id", x.id); put("text", x.text) }
        is UiItem.Assistant -> buildJsonObject { put("k", "assistant"); put("id", x.id); put("text", x.text) }
        is UiItem.Tool -> buildJsonObject {
            put("k", "tool")
            put("id", x.id)
            put("call", call(x.call))
            put("status", x.status.name)
            x.output?.let { put("output", it.take(MAX_OUTPUT)) }
            put("ms", x.durationMs)
        }
        is UiItem.Notice -> buildJsonObject { put("k", "notice"); put("id", x.id); put("text", x.text); put("error", x.isError) }
        is UiItem.Files -> buildJsonObject {
            put("k", "files")
            put("id", x.id)
            put("created", buildJsonArray { x.created.forEach { add(JsonPrimitive(it)) } })
            put("updated", buildJsonArray { x.updated.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun message(m: ChatMessage): JsonObject = when (m) {
        is ChatMessage.User -> buildJsonObject { put("t", "user"); put("text", m.text) }
        is ChatMessage.Assistant -> buildJsonObject {
            put("t", "assistant")
            put("text", m.text)
            put("calls", buildJsonArray { m.toolCalls.forEach { add(call(it)) } })
        }
        is ChatMessage.Tool -> buildJsonObject {
            put("t", "tool")
            put(
                "results",
                buildJsonArray {
                    m.results.forEach { r ->
                        add(
                            buildJsonObject {
                                put("callId", r.callId)
                                put("name", r.name)
                                put("output", r.output.take(MAX_OUTPUT))
                                put("isError", r.isError)
                            },
                        )
                    }
                },
            )
        }
    }

    // ------------------------------------------------------------ decode

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun JsonObject.l(k: String): Long = s(k)?.toLongOrNull() ?: 0L
    private fun JsonObject.b(k: String): Boolean = s(k) == "true"
    private fun JsonObject.strings(k: String): List<String> =
        (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    private fun decodeCall(o: JsonObject): ToolCall? {
        val id = o.s("id") ?: return null
        val name = o.s("name") ?: return null
        return ToolCall(id, name, (o["args"] as? JsonObject) ?: JsonObject(emptyMap()), o.s("extra"))
    }

    private fun decodeItem(o: JsonObject): UiItem? {
        val id = o.l("id")
        return when (o.s("k")) {
            "user" -> UiItem.User(id, o.s("text").orEmpty())
            "assistant" -> UiItem.Assistant(id, o.s("text").orEmpty(), false)
            "tool" -> {
                val c = decodeCall(o["call"] as? JsonObject ?: return null) ?: return null
                val saved = runCatching { ToolStatus.valueOf(o.s("status").orEmpty()) }.getOrDefault(ToolStatus.FAILED)
                val cut = saved == ToolStatus.QUEUED || saved == ToolStatus.AWAITING || saved == ToolStatus.RUNNING
                UiItem.Tool(
                    id = id,
                    call = c,
                    status = if (cut) ToolStatus.FAILED else saved,
                    output = o.s("output") ?: if (cut) "Interrupted" else null,
                    durationMs = o.l("ms"),
                )
            }
            "notice" -> UiItem.Notice(id, o.s("text").orEmpty(), o.b("error"))
            "files" -> UiItem.Files(id, o.strings("created"), o.strings("updated"))
            else -> null
        }
    }

    private fun decodeMessage(o: JsonObject): ChatMessage? = when (o.s("t")) {
        "user" -> ChatMessage.User(o.s("text").orEmpty())
        "assistant" -> ChatMessage.Assistant(
            o.s("text").orEmpty(),
            (o["calls"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { c -> decodeCall(c) } },
        )
        "tool" -> ChatMessage.Tool(
            (o["results"] as? JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonObject)?.let { r ->
                    ToolResult(r.s("callId").orEmpty(), r.s("name").orEmpty(), r.s("output").orEmpty(), r.b("isError"))
                }
            },
        )
        else -> null
    }
}

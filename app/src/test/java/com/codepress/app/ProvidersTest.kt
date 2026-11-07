package com.codepress.app

import com.codepress.app.agent.ChatMessage
import com.codepress.app.agent.ToolCall
import com.codepress.app.agent.ToolResult
import com.codepress.app.agent.str
import com.codepress.app.llm.AnthropicProvider
import com.codepress.app.llm.OpenAiProvider
import com.codepress.app.llm.SseParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {

    private fun events(vararg lines: String): List<Pair<String?, String>> {
        val out = mutableListOf<Pair<String?, String>>()
        val p = SseParser { e, d -> out += e to d }
        lines.forEach { p.line(it) }
        p.finish()
        return out
    }

    @Test
    fun sseParserGroupsEvents() {
        val ev = events("event: a", "data: {\"x\":1}", "", ": comment", "data: [DONE]", "")
        assertEquals(listOf<Pair<String?, String>>("a" to "{\"x\":1}", null to "[DONE]"), ev)
    }

    @Test
    fun anthropicStreamWithTextAndToolUse() {
        var shown = ""
        val acc = AnthropicProvider.Accumulator { shown = it }
        val stream = listOf(
            "content_block_start" to "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "content_block_delta" to "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}",
            "content_block_delta" to "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}",
            "content_block_start" to "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_file\",\"input\":{}}}",
            "content_block_delta" to "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\": \\\"a\"}}",
            "content_block_delta" to "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\".txt\\\"}\"}}",
            "message_delta" to "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}",
        )
        stream.forEach { (e, d) -> acc.onEvent(e, d) }
        val turn = acc.result()
        assertEquals("Hello", shown)
        assertEquals("Hello", turn.text)
        assertEquals(1, turn.toolCalls.size)
        assertEquals("toolu_1", turn.toolCalls[0].id)
        assertEquals("a.txt", turn.toolCalls[0].arguments.str("path"))
        assertEquals("tool_use", acc.stopReason)
    }

    @Test
    fun openAiStreamAccumulatesToolCallArguments() {
        val acc = OpenAiProvider.Accumulator { }
        acc.onData("{\"choices\":[{\"delta\":{\"content\":\"Ok\"}}]}")
        acc.onData("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"function\":{\"name\":\"run_command\",\"arguments\":\"{\\\"command\\\":\"}}]}}]}")
        acc.onData("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\" \\\"ls\\\"}\"}}]}}]}")
        acc.onData("[DONE]")
        val turn = acc.result()
        assertEquals("Ok", turn.text)
        assertEquals("call_9", turn.toolCalls[0].id)
        assertEquals("ls", turn.toolCalls[0].arguments.str("command"))
    }

    private val history = listOf(
        ChatMessage.User("hi"),
        ChatMessage.Assistant("checking", listOf(ToolCall("t1", "read_file", buildJsonObject { put("path", "a") }))),
        ChatMessage.Tool(listOf(ToolResult("t1", "read_file", "contents", false))),
    )

    @Test
    fun anthropicMessagesPairToolUseWithToolResult() {
        val msgs: JsonArray = AnthropicProvider.toMessages(history)
        assertEquals(3, msgs.size)
        val assistant = msgs[1] as JsonObject
        val content = assistant["content"] as JsonArray
        assertEquals("text", (content[0] as JsonObject).str("type"))
        assertEquals("tool_use", (content[1] as JsonObject).str("type"))
        val result = ((msgs[2] as JsonObject)["content"] as JsonArray)[0] as JsonObject
        assertEquals("tool_result", result.str("type"))
        assertEquals("t1", result.str("tool_use_id"))
    }

    @Test
    fun openAiMessagesUseToolRole() {
        val msgs = OpenAiProvider.toMessages("sys", history)
        assertEquals(4, msgs.size)
        assertEquals("system", (msgs[0] as JsonObject).str("role"))
        assertTrue((msgs[1] as JsonObject).str("role") == "user")
        assertEquals("tool", (msgs[3] as JsonObject).str("role"))
        assertEquals("t1", (msgs[3] as JsonObject).str("tool_call_id"))
    }
}

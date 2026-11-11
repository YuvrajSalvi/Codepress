package com.codepress.app

import com.codepress.app.agent.ToolProtocol
import com.codepress.app.agent.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProtocolTest {
    private val known = setOf("read_file", "run_command")

    @Test
    fun parsesWellFormedCall() {
        val raw = "Let me look.\n<tool_call>\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.txt\"}}\n</tool_call>"
        val calls = ToolProtocol.parse(raw, known)
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        assertEquals("a.txt", calls[0].arguments.str("path"))
        assertEquals("Let me look.", ToolProtocol.visibleText(raw))
    }

    @Test
    fun parsesCallWithoutClosingTagAndBracesInStrings() {
        val raw = "<tool_call>{\"name\": \"run_command\", \"arguments\": {\"command\": \"echo '}' && ls\"}}"
        val calls = ToolProtocol.parse(raw, known)
        assertEquals(1, calls.size)
        assertEquals("echo '}' && ls", calls[0].arguments.str("command"))
    }

    @Test
    fun ignoresUnknownToolsAndGarbage() {
        val raw = "<tool_call>{\"name\": \"format_disk\", \"arguments\": {}}</tool_call><tool_call>not json</tool_call>"
        assertTrue(ToolProtocol.parse(raw, known).isEmpty())
    }

    @Test
    fun parsesTwoCalls() {
        val raw = "<tool_call>{\"name\":\"read_file\",\"arguments\":{\"path\":\"a\"}}</tool_call>" +
            "<tool_call>{\"name\":\"read_file\",\"arguments\":{\"path\":\"b\"}}</tool_call>"
        val calls = ToolProtocol.parse(raw, known)
        assertEquals(listOf("a", "b"), calls.map { it.arguments.str("path") })
        assertEquals(2, calls.map { it.id }.toSet().size)
    }
}

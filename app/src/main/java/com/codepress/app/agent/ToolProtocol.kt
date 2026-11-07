package com.codepress.app.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Text based tool calling for local models, which have no native tool API.
 * The model is told to answer with:
 *   <tool_call>{"name": "read_file", "arguments": {"path": "a.txt"}}</tool_call>
 * (the same shape Qwen / Hermes style models are trained on).
 */
object ToolProtocol {
    const val OPEN = "<tool_call>"
    const val CLOSE = "</tool_call>"

    /** Text shown to the user: everything before the first tool call. */
    fun visibleText(raw: String): String = raw.substringBefore(OPEN).trim()

    fun parse(raw: String, knownTools: Set<String>): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        var from = 0
        while (true) {
            val open = raw.indexOf(OPEN, from)
            if (open < 0) break
            var i = open + OPEN.length
            while (i < raw.length && raw[i].isWhitespace()) i++
            val extracted = if (i < raw.length && raw[i] == '{') extractJson(raw, i) else null
            if (extracted == null) {
                from = open + OPEN.length
                continue
            }
            val (json, end) = extracted
            from = end
            val obj = parseObject(json) ?: continue
            val name = obj.str("name") ?: continue
            if (name !in knownTools) continue
            val args: JsonObject = when (val a = obj["arguments"] ?: obj["parameters"]) {
                is JsonObject -> a
                null -> JsonObject(emptyMap())
                is JsonPrimitive -> parseObject(a.content) ?: JsonObject(emptyMap())
                else -> JsonObject(emptyMap())
            }
            calls += ToolCall("local_${calls.size + 1}", name, args)
        }
        return calls
    }

    /** Returns the balanced JSON object that starts at [start] and the index just after it, or null. */
    fun extractJson(s: String, start: Int): Pair<String, Int>? {
        if (start >= s.length || s[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(start, i + 1) to (i + 1)
                    }
                }
            }
        }
        return null
    }

    fun instructions(specs: List<ToolSpec>): String = buildString {
        appendLine("# Tools")
        appendLine("You can use tools. To call a tool, reply with exactly this format and then stop and wait for the result:")
        appendLine("$OPEN{\"name\": \"<tool name>\", \"arguments\": {<arguments as JSON>}}$CLOSE")
        appendLine("Call one tool at a time. After the result arrives you may call another tool or give your final answer.")
        appendLine("Available tools:")
        for (s in specs) {
            appendLine("- ${s.name}: ${s.description}")
            appendLine("  arguments schema: ${s.schema}")
        }
    }

    fun formatResults(results: List<ToolResult>, maxCharsEach: Int): String = buildString {
        for (r in results) {
            append("<tool_result name=\"").append(r.name).append("\"")
            if (r.isError) append(" error=\"true\"")
            append(">\n")
            append(ToolExecutor.truncate(r.output, maxCharsEach))
            append("\n</tool_result>\n")
        }
        append("Continue with the task.")
    }
}

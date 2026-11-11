package com.codepress.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Small Markdown renderer for AI answers:
 *   # / ## / ###  -> headings      **text** -> bold       *text* -> italic
 *   `code`        -> code chip     - item   -> bullets    1. item -> numbered list
 *   > quote, ---, ~~strike~~, [label](url), simple | tables |
 * Fenced ``` blocks are handled by the caller (they get the CodeBlock with Copy / Run).
 */

private sealed interface Block {
    class Heading(val level: Int, val text: String) : Block
    class Para(val text: String) : Block
    class Bullet(val indent: Int, val text: String) : Block
    class Numbered(val indent: Int, val number: String, val text: String) : Block
    class Quote(val text: String) : Block
    class Table(val lines: List<String>) : Block
    object Rule : Block
}

private val headingRe = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRe = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val numberedRe = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val ruleRe = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

private fun parseBlocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) {
            out += Block.Para(para.toString().trimEnd())
            para.clear()
        }
    }
    val lines = src.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        val trimmed = line.trimStart()
        when {
            line.isBlank() -> flush()
            ruleRe.matches(line) -> {
                flush()
                out += Block.Rule
            }
            headingRe.matches(line) -> {
                flush()
                val m = headingRe.find(line)!!
                out += Block.Heading(m.groupValues[1].length, m.groupValues[2].trim().trimEnd('#').trim())
            }
            bulletRe.matches(line) -> {
                flush()
                val m = bulletRe.find(line)!!
                out += Block.Bullet(m.groupValues[1].length, m.groupValues[2])
            }
            numberedRe.matches(line) -> {
                flush()
                val m = numberedRe.find(line)!!
                out += Block.Numbered(m.groupValues[1].length, m.groupValues[2], m.groupValues[3])
            }
            trimmed.startsWith(">") -> {
                flush()
                out += Block.Quote(trimmed.removePrefix(">").trim())
            }
            trimmed.startsWith("|") -> {
                flush()
                val rows = mutableListOf(line)
                while (i + 1 < lines.size && lines[i + 1].trimStart().startsWith("|")) {
                    i++
                    rows += lines[i].trimEnd()
                }
                out += Block.Table(rows)
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line.trim())
            }
        }
        i++
    }
    flush()
    return out
}

private class Palette(val codeBg: Color, val codeFg: Color, val link: Color, val muted: Color)

private fun AnnotatedString.Builder.appendInline(s: String, p: Palette) {
    var i = 0
    val n = s.length
    while (i < n) {
        val c = s[i]
        if (c == '\\' && i + 1 < n && s[i + 1] in "\\`*_{}[]()#+-.!~>|") {
            append(s[i + 1])
            i += 2
            continue
        }
        if (c == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = p.codeBg, color = p.codeFg, fontSize = 13.sp)) {
                    append("\u00A0" + s.substring(i + 1, end) + "\u00A0")
                }
                i = end + 1
                continue
            }
        }
        if (s.startsWith("**", i) || s.startsWith("__", i)) {
            val mark = s.substring(i, i + 2)
            val end = s.indexOf(mark, i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(s.substring(i + 2, end), p) }
                i = end + 2
                continue
            }
        }
        if (s.startsWith("~~", i)) {
            val end = s.indexOf("~~", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(s.substring(i + 2, end), p) }
                i = end + 2
                continue
            }
        }
        if (c == '*' && i + 1 < n && s[i + 1] != ' ' && s[i + 1] != '*') {
            var j = i + 1
            var end = -1
            while (j < n) {
                if (s[j] == '*' && s[j - 1] != ' ' && (j + 1 >= n || s[j + 1] != '*')) {
                    end = j
                    break
                }
                j++
            }
            if (end > i + 1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(s.substring(i + 1, end), p) }
                i = end + 1
                continue
            }
        }
        if (c == '[') {
            val close = s.indexOf(']', i + 1)
            if (close > i && close + 1 < n && s[close + 1] == '(') {
                val endParen = s.indexOf(')', close + 2)
                if (endParen > close) {
                    val label = s.substring(i + 1, close)
                    val url = s.substring(close + 2, endParen)
                    withStyle(SpanStyle(color = p.link, textDecoration = TextDecoration.Underline)) { appendInline(label, p) }
                    if (label != url) withStyle(SpanStyle(color = p.muted, fontSize = 12.sp)) { append(" ($url)") }
                    i = endParen + 1
                    continue
                }
            }
        }
        append(c)
        i++
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    val cs = MaterialTheme.colorScheme
    val pal = remember(cs) { Palette(cs.surfaceContainerHighest, Color(0xFFB9C0FF), Color(0xFF8E98F5), cs.onSurfaceVariant) }
    val body = MaterialTheme.typography.bodyLarge

    fun inline(t: String): AnnotatedString = buildAnnotatedString { appendInline(t, pal) }

    SelectionContainer {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is Block.Heading -> {
                        val size = when (b.level) {
                            1 -> 21f
                            2 -> 18f
                            3 -> 16.5f
                            else -> 15f
                        }
                        Text(
                            inline(b.text),
                            style = body.copy(fontSize = size.sp, lineHeight = (size * 1.35f).sp, fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(top = if (b.level <= 2) 8.dp else 4.dp),
                        )
                    }
                    is Block.Para -> Text(inline(b.text), style = body)
                    is Block.Bullet -> Row(Modifier.padding(start = ((b.indent / 2).coerceAtMost(4) * 16).dp)) {
                        Text("•", style = body, modifier = Modifier.width(18.dp))
                        Text(inline(b.text), style = body, modifier = Modifier.weight(1f))
                    }
                    is Block.Numbered -> Row(Modifier.padding(start = ((b.indent / 2).coerceAtMost(4) * 16).dp)) {
                        Text("${b.number}.", style = body, modifier = Modifier.width(26.dp))
                        Text(inline(b.text), style = body, modifier = Modifier.weight(1f))
                    }
                    is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(cs.outline))
                        Text(
                            inline(b.text),
                            style = body.copy(fontStyle = FontStyle.Italic),
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                    is Block.Table -> Text(
                        b.lines.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                    Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = cs.outlineVariant)
                }
            }
        }
    }
}

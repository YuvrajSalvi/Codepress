package com.codepress.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.codepress.app.ui.autoEdit
import com.codepress.app.ui.findAll
import com.codepress.app.ui.languageOf
import com.codepress.app.ui.matchBracket
import com.codepress.app.ui.runSpecFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CodeEditingTest {
    private fun typed(old: String, cursor: Int, ch: String): Pair<TextFieldValue, TextFieldValue> {
        val o = TextFieldValue(old, TextRange(cursor))
        val n = TextFieldValue(old.substring(0, cursor) + ch + old.substring(cursor), TextRange(cursor + ch.length))
        return o to n
    }

    @Test
    fun closesBracketsAndTypesOverThem() {
        val (o, n) = typed("fun a", 5, "(")
        val r = autoEdit(o, n, "clike")
        assertEquals("fun a()", r.text)
        assertEquals(6, r.selection.start)
        val (o2, n2) = typed("fun a()", 6, ")")
        val r2 = autoEdit(o2, n2, "clike")
        assertEquals("fun a()", r2.text)
        assertEquals(7, r2.selection.start)
    }

    @Test
    fun keepsIndentationAndIndentsAfterBrace() {
        val (o, n) = typed("  if (x) {", 10, "\n")
        val r = autoEdit(o, n, "clike")
        assertEquals("  if (x) {\n      ", r.text)
        val (o2, n2) = typed("    x = 1", 9, "\n")
        assertEquals("    x = 1\n    ", autoEdit(o2, n2, "python").text)
    }

    @Test
    fun apostropheInsideWordIsNotClosed() {
        val (o, n) = typed("don", 3, "'")
        assertEquals("don'", autoEdit(o, n, "text").text)
    }

    @Test
    fun helpers() {
        assertEquals("python", languageOf("src/main.py"))
        assertEquals("html", languageOf("index.html"))
        assertEquals(listOf(0, 4), findAll("ab ab", "ab").let { listOf(it[0], it[1] + 1) })
        assertEquals(1 to 5, matchBracket("a(bcd)e", 1)?.let { it.first to it.second })
        assertNotNull(runSpecFor("main.py"))
        assertNull(runSpecFor("notes.txt"))
    }
}

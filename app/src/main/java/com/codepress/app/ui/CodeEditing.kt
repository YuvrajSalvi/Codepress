package com.codepress.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private val CKW = "abstract|as|break|case|catch|class|const|continue|default|do|else|enum|export|extends|false|final|finally|for|fun|function|if|implements|import|in|interface|is|let|new|null|object|override|package|private|protected|public|return|static|super|switch|this|throw|true|try|typeof|val|var|void|when|while|yield|async|await|from|of|struct|impl|fn|mut|pub|use|mod|trait|match|func|go|defer|range|chan|select|type|namespace|using|include|define|template|typename|inline|volatile|unsigned|signed|int|long|short|char|float|double|bool|string|boolean|byte|lambda|def|elif|except|pass|raise|with|None|True|False|and|or|not|echo|fi|then|esac|done|local|end|begin|module|require|nil|self|unless|puts"

/** Language id for a file name. */
fun languageOf(path: String): String = when (path.substringAfterLast('/').substringAfterLast('.', path.substringAfterLast('/')).lowercase()) {
    "html", "htm", "xhtml", "vue", "svelte" -> "html"
    "xml", "svg", "xsd", "plist", "iml" -> "xml"
    "css", "scss", "less" -> "css"
    "js", "jsx", "mjs", "cjs", "ts", "tsx" -> "js"
    "json", "jsonc" -> "json"
    "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc", "cs", "rs", "go", "swift", "dart", "scala", "gradle" -> "clike"
    "php" -> "php"
    "py", "pyw" -> "python"
    "rb" -> "ruby"
    "sh", "bash", "zsh", "env", "properties", "ini", "cfg", "conf" -> "shell"
    "yaml", "yml", "toml" -> "yaml"
    "sql" -> "sql"
    "md", "markdown" -> "markdown"
    else -> "text"
}

/** Short badge text for the file type icon. */
fun badgeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "py" -> "PY"; "java" -> "JV"; "kt", "kts" -> "KT"; "c", "h" -> "C"; "cpp", "cc", "hpp" -> "C++"
    "go" -> "GO"; "rs" -> "RS"; "php" -> "PHP"; "rb" -> "RB"; "sh", "bash" -> "SH"; "sql" -> "SQL"
    "md" -> "MD"; "json" -> "{ }"; "yaml", "yml", "toml" -> "YML"; "ts", "tsx" -> "TS"; "xml" -> "XML"
    "txt" -> "TXT"; "env" -> "ENV"; "gradle" -> "GR"; "cs" -> "C#"; "swift" -> "SW"; "dart" -> "DT"
    else -> ""
}

data class SyntaxColors(val keyword: Color, val string: Color, val number: Color, val tag: Color, val comment: Color)

val DarkSyntax = SyntaxColors(
    keyword = Color(0xFFA5B0FF), string = Color(0xFF7EE787), number = Color(0xFFFFB86C),
    tag = Color(0xFF79C0FF), comment = Color(0xFF7C8594),
)

private val STRING = """"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`"""
private val NUMBER = """\b\d+(?:\.\d+)?\b"""

private fun commentFor(lang: String) = when (lang) {
    "html", "xml" -> "<!--[\\s\\S]*?-->"
    "css" -> "/\\*[\\s\\S]*?\\*/"
    "python", "ruby", "shell", "yaml" -> "#[^\\n]*"
    "sql" -> "--[^\\n]*|/\\*[\\s\\S]*?\\*/"
    "php" -> "//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/"
    "json", "markdown", "text" -> "(?!x)x"
    else -> "//[^\\n]*|/\\*[\\s\\S]*?\\*/"
}

private val regexCache = HashMap<String, Regex>()

private fun regexFor(lang: String): Regex = regexCache.getOrPut(lang) {
    val tags = if (lang == "html" || lang == "xml") "</?[A-Za-z][\\w:.-]*|/?>" else "(?!x)x"
    val kw = if (lang == "json" || lang == "markdown" || lang == "text" || lang == "html" || lang == "xml") "(?!x)x" else "\\b(?:$CKW)\\b"
    Regex("(${commentFor(lang)})|($STRING)|($NUMBER)|($tags)|($kw)", RegexOption.IGNORE_CASE.takeIf { lang == "sql" } ?: RegexOption.MULTILINE)
}

fun highlight(text: String, lang: String, c: SyntaxColors = DarkSyntax): AnnotatedString {
    if (lang == "text" || text.length > 150_000) return AnnotatedString(text)
    val rx = regexFor(lang)
    return buildAnnotatedString {
        append(text)
        for (m in rx.findAll(text)) {
            val color = when {
                m.groups[1] != null -> c.comment
                m.groups[2] != null -> c.string
                m.groups[3] != null -> c.number
                m.groups[4] != null -> c.tag
                else -> c.keyword
            }
            addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
        }
    }
}

private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}')

/** Position of the bracket that matches the one at [pos] (or just before it), or null. */
fun matchBracket(text: String, cursor: Int): Pair<Int, Int>? {
    fun find(at: Int): Pair<Int, Int>? {
        if (at < 0 || at >= text.length) return null
        val ch = text[at]
        val open = PAIRS[ch]
        if (open != null) {
            var depth = 0
            for (i in at until minOf(text.length, at + 20000)) {
                if (text[i] == ch) depth++ else if (text[i] == open) { depth--; if (depth == 0) return at to i }
            }
        } else {
            val closer = PAIRS.entries.firstOrNull { it.value == ch }?.key ?: return null
            var depth = 0
            for (i in at downTo maxOf(0, at - 20000)) {
                if (text[i] == ch) depth++ else if (text[i] == closer) { depth--; if (depth == 0) return i to at }
            }
        }
        return null
    }
    return find(cursor) ?: find(cursor - 1)
}

/** Syntax colours plus a highlight on the bracket pair next to the cursor. */
class SyntaxTransformation(private val lang: String, private val cursor: Int, private val matchBg: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val base = highlight(text.text, lang)
        val pair = if (text.length < 100_000) matchBracket(text.text, cursor) else null
        val out = if (pair == null) base else buildAnnotatedString {
            append(base)
            addStyle(SpanStyle(background = matchBg), pair.first, pair.first + 1)
            addStyle(SpanStyle(background = matchBg), pair.second, pair.second + 1)
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}

fun withMatches(base: AnnotatedString, query: String, bg: Color, current: Int = -1): AnnotatedString {
    if (query.isEmpty()) return base
    val hits = findAll(base.text, query)
    if (hits.isEmpty()) return base
    return buildAnnotatedString {
        append(base)
        hits.forEachIndexed { i, s ->
            addStyle(SpanStyle(background = if (i == current) bg.copy(alpha = 0.85f) else bg.copy(alpha = 0.35f)), s, s + query.length)
        }
    }
}

fun findAll(text: String, query: String, ignoreCase: Boolean = true): List<Int> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var i = text.indexOf(query, 0, ignoreCase)
    while (i >= 0 && out.size < 5000) {
        out += i
        i = text.indexOf(query, i + query.length, ignoreCase)
    }
    return out
}

private val CLOSERS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')

/**
 * Editing helpers for a phone keyboard: keeps indentation on Enter, closes brackets and quotes,
 * and types over a closing character that is already there.
 */
fun autoEdit(old: TextFieldValue, new: TextFieldValue, lang: String): TextFieldValue {
    if (!old.selection.collapsed || !new.selection.collapsed) return new
    if (new.text.length != old.text.length + 1) return new
    val pos = new.selection.start
    if (pos < 1 || pos > new.text.length) return new
    if (new.text.substring(0, pos - 1) != old.text.substring(0, pos - 1)) return new
    val ch = new.text[pos - 1]
    val before = old.text.substring(0, pos - 1)
    val after = old.text.substring(pos - 1)

    if (ch == '\n') {
        val lineStart = before.lastIndexOf('\n') + 1
        val line = before.substring(lineStart)
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val last = line.trimEnd().lastOrNull()
        val opens = last != null && (last in "{([" || (last == ':' && (lang == "python" || lang == "yaml")))
        val extra = if (opens) "    " else ""
        val nextCh = after.firstOrNull()
        return if (opens && nextCh != null && nextCh in "})]" && last != ':') {
            val text = before + "\n" + indent + extra + "\n" + indent + after
            TextFieldValue(text, TextRange(before.length + 1 + indent.length + extra.length))
        } else {
            val text = before + "\n" + indent + extra + after
            TextFieldValue(text, TextRange(before.length + 1 + indent.length + extra.length))
        }
    }
    val nextCh = after.firstOrNull()
    if (ch in ")]}\"'`" && nextCh == ch) {
        // typing over the closing character that is already there
        return TextFieldValue(old.text, TextRange(pos))
    }
    val closer = CLOSERS[ch]
    if (closer != null) {
        val prev = before.lastOrNull()
        val isQuote = ch == '"' || ch == '\'' || ch == '`'
        val skip = isQuote && (prev != null && (prev.isLetterOrDigit() || prev == '\\')) || (nextCh != null && nextCh.isLetterOrDigit())
        if (!skip) return TextFieldValue(before + ch + closer + after, TextRange(pos))
    }
    return new
}

fun insertAtCursor(v: TextFieldValue, s: String, cursorBack: Int = 0): TextFieldValue {
    val sel = v.selection
    val text = v.text.replaceRange(sel.min, sel.max, s)
    return TextFieldValue(text, TextRange(sel.min + s.length - cursorBack))
}

/** Extra keys above the keyboard, depending on the language. Each is (label, text to insert, cursor steps back). */
fun shortcutKeys(lang: String): List<Triple<String, String, Int>> {
    val common = listOf(Triple("Tab", "    ", 0), Triple("( )", "()", 1), Triple("[ ]", "[]", 1), Triple("{ }", "{}", 1), Triple("\" \"", "\"\"", 1), Triple("' '", "''", 1))
    return when (lang) {
        "python" -> listOf(Triple("Tab", "    ", 0), Triple(":", ":", 0), Triple("( )", "()", 1), Triple("[ ]", "[]", 1), Triple("{ }", "{}", 1), Triple("#", "#", 0), Triple("_", "_", 0), Triple("\" \"", "\"\"", 1), Triple("' '", "''", 1), Triple("=", "=", 0))
        "html", "xml" -> listOf(Triple("Tab", "  ", 0), Triple("< >", "<>", 1), Triple("</", "</", 0), Triple("/>", "/>", 0), Triple("=", "=", 0), Triple("\" \"", "\"\"", 1), Triple("{ }", "{}", 1), Triple("( )", "()", 1), Triple(":", ":", 0), Triple(";", ";", 0))
        "js" -> listOf(Triple("Tab", "  ", 0), Triple("{ }", "{}", 1), Triple("( )", "()", 1), Triple("[ ]", "[]", 1), Triple("=>", "=>", 0), Triple(";", ";", 0), Triple("=", "=", 0), Triple("==", "===", 0), Triple("` `", "``", 1), Triple("\" \"", "\"\"", 1), Triple("' '", "''", 1))
        "css" -> listOf(Triple("Tab", "  ", 0), Triple("{ }", "{}", 1), Triple(":", ":", 0), Triple(";", ";", 0), Triple("#", "#", 0), Triple(".", ".", 0), Triple("( )", "()", 1), Triple("%", "%", 0), Triple("\" \"", "\"\"", 1))
        "shell" -> listOf(Triple("Tab", "  ", 0), Triple("$", "$", 0), Triple("\" \"", "\"\"", 1), Triple("' '", "''", 1), Triple("|", "|", 0), Triple("&&", " && ", 0), Triple(">", ">", 0), Triple("/", "/", 0), Triple("#", "#", 0), Triple("( )", "()", 1))
        else -> common + listOf(Triple(";", ";", 0), Triple("=", "=", 0), Triple("<>", "<>", 1), Triple("/", "/", 0), Triple(":", ":", 0))
    }
}

/** Command to run a file with, and the program that must exist for it. Null when the file type is not runnable. */
data class RunSpec(val needs: List<String>, val label: String, val anyOf: Boolean = false, val command: (String) -> String)

fun runSpecFor(path: String): RunSpec? {
    val ext = path.substringAfterLast('.', "").lowercase()
    val name = path.substringAfterLast('/')
    val stem = name.substringBeforeLast('.')
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    return when (ext) {
        "py" -> RunSpec(listOf("python3", "python"), "Python", true) { f -> "${'$'}(command -v python3 || command -v python) ${q(f)}" }
        "js", "mjs", "cjs" -> RunSpec(listOf("node"), "Node.js") { f -> "node ${q(f)}" }
        "sh", "bash" -> RunSpec(listOf("sh"), "Shell") { f -> "sh ${q(f)}" }
        "php" -> RunSpec(listOf("php"), "PHP") { f -> "php ${q(f)}" }
        "rb" -> RunSpec(listOf("ruby"), "Ruby") { f -> "ruby ${q(f)}" }
        "go" -> RunSpec(listOf("go"), "Go") { f -> "go run ${q(f)}" }
        "c" -> RunSpec(listOf("gcc"), "C (gcc)") { f -> "gcc ${q(f)} -o ${q(stem)} && ./${stem.replace(" ", "\\ ")}" }
        "cpp", "cc" -> RunSpec(listOf("g++"), "C++ (g++)") { f -> "g++ ${q(f)} -o ${q(stem)} && ./${stem.replace(" ", "\\ ")}" }
        "java" -> RunSpec(listOf("javac", "java"), "Java") { f -> "javac ${q(f)} && java ${q(stem)}" }
        "rs" -> RunSpec(listOf("rustc"), "Rust") { f -> "rustc ${q(f)} -o ${q(stem)} && ./${stem.replace(" ", "\\ ")}" }
        else -> null
    }
}

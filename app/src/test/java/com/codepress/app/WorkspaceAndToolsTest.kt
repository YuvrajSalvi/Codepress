package com.codepress.app

import com.codepress.app.agent.ToolCall
import com.codepress.app.agent.ToolExecutor
import com.codepress.app.agent.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceAndToolsTest {
    private lateinit var root: File
    private lateinit var ws: Workspace
    private lateinit var tools: ToolExecutor

    @Before
    fun setUp() {
        root = Files.createTempDirectory("codepress-test").toFile()
        ws = Workspace(root)
        tools = ToolExecutor(ws)
    }

    private fun call(name: String, vararg args: Pair<String, String>) =
        ToolCall("id", name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

    private fun run(name: String, vararg args: Pair<String, String>) = runBlocking { tools.execute(call(name, *args)) }

    @Test
    fun resolveRejectsEscapes() {
        try {
            ws.resolve("../outside.txt")
            fail("expected an exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("outside"))
        }
        // absolute-looking paths are treated as relative to the workspace
        assertTrue(ws.resolve("/etc/passwd").path.startsWith(ws.root.path))
        assertTrue(ws.resolve("a/../b.txt").path.startsWith(ws.root.path))
    }

    @Test
    fun writeReadEditRoundTrip() {
        val w = run("write_file", "path" to "src/hello.txt", "content" to "one\ntwo\nthree\n")
        assertFalse(w.isError)
        val r = run("read_file", "path" to "src/hello.txt")
        assertTrue(r.output.contains("two"))
        val e = run("edit_file", "path" to "src/hello.txt", "old_text" to "two", "new_text" to "2")
        assertFalse(e.output, e.isError)
        assertEquals("one\n2\nthree\n", File(root, "src/hello.txt").readText())
    }

    @Test
    fun editRequiresExactlyOneMatch() {
        File(root, "x.txt").writeText("a a")
        assertTrue(run("edit_file", "path" to "x.txt", "old_text" to "a", "new_text" to "b").isError)
        assertTrue(run("edit_file", "path" to "x.txt", "old_text" to "zzz", "new_text" to "b").isError)
        assertEquals("a a", File(root, "x.txt").readText())
    }

    @Test
    fun fileToolsCannotLeaveWorkspace() {
        val r = run("read_file", "path" to "../../etc/hostname")
        assertTrue(r.isError)
    }

    @Test
    fun searchFindsMatches() {
        File(root, "a.kt").writeText("fun main() {}\nval x = 1\n")
        val r = run("search_files", "pattern" to "val\\s+x")
        assertTrue(r.output.contains("a.kt:2"))
    }

    @Test
    fun runCommandCapturesOutputAndExitCode() {
        val r = run("run_command", "command" to "echo hello")
        assertTrue(r.output.contains("hello"))
        assertTrue(r.output.contains("[exit code 0]"))
    }

    @Test
    fun onlyChangingToolsNeedApproval() {
        assertTrue(tools.needsApproval("run_command"))
        assertTrue(tools.needsApproval("write_file"))
        assertTrue(tools.needsApproval("edit_file"))
        assertFalse(tools.needsApproval("read_file"))
        assertFalse(tools.needsApproval("list_dir"))
        assertFalse(tools.needsApproval("search_files"))
    }
}

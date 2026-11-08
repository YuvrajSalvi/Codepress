package com.codepress.app

import com.codepress.app.agent.ShellOrigin
import com.codepress.app.agent.ShellRunner
import com.codepress.app.agent.ShellState
import com.codepress.app.agent.Workspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ShellRunnerTest {
    private fun newShell(): Pair<File, ShellRunner> {
        val root = Files.createTempDirectory("codepress-shell").toFile()
        return root to ShellRunner(Workspace(root))
    }

    @Test
    fun userCdStaysInsideWorkspaceAndChangesDirectory() = runBlocking {
        val (root, shell) = newShell()
        File(root, "sub").mkdirs()
        val ok = shell.run("cd sub", ShellOrigin.USER)
        assertEquals(ShellState.COMPLETED, ok.state)
        assertEquals("~/sub", shell.cwdLabel.value)
        val pwd = shell.run("pwd", ShellOrigin.USER)
        assertTrue(pwd.output.endsWith("sub"))
        val bad = shell.run("cd ../..", ShellOrigin.USER)
        assertEquals(ShellState.FAILED, bad.state)
        assertEquals("~/sub", shell.cwdLabel.value)
    }

    @Test
    fun recordsOutputExitCodeAndHistory() = runBlocking {
        val (_, shell) = newShell()
        val e = shell.run("echo hi; exit 3", ShellOrigin.USER)
        assertTrue(e.output.contains("hi"))
        assertEquals(3, e.exitCode)
        assertEquals(ShellState.FAILED, e.state)
        assertEquals(listOf("echo hi; exit 3"), shell.history.value)
        assertEquals(1, shell.entries.value.size)
    }

    @Test
    fun aiCommandsAreMarkedAndStartInWorkspaceRoot() = runBlocking {
        val (root, shell) = newShell()
        File(root, "sub").mkdirs()
        shell.run("cd sub", ShellOrigin.USER)
        val e = shell.run("pwd", ShellOrigin.AI, tag = "t1")
        assertEquals(ShellOrigin.AI, e.origin)
        assertEquals("t1", e.tag)
        assertTrue(!e.output.endsWith("sub"))
    }
}

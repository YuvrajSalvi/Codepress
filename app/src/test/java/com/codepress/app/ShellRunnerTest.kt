package com.codepress.app

import com.codepress.app.agent.ShellOrigin
import com.codepress.app.agent.ShellRunner
import com.codepress.app.agent.ShellState
import com.codepress.app.agent.Workspace
import com.codepress.app.agent.FileOps
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

class FileOpsTest {
    private fun ws(): Pair<File, com.codepress.app.agent.Workspace> {
        val root = Files.createTempDirectory("codepress-ops").toFile()
        return root to Workspace(root)
    }

    @Test
    fun zipRoundTripKeepsFolderStructure() {
        val (root, w) = ws()
        File(root, "site/css").mkdirs()
        File(root, "site/index.html").writeText("<h1>hi</h1>")
        File(root, "site/css/a.css").writeText("body{}")
        val ops = com.codepress.app.agent.FileOps(w)
        ops.zipToFile("site.zip", listOf("site"))
        File(root, "out").mkdirs()
        val n = ops.unzip("site.zip", "out")
        assertEquals(2, n)
        assertEquals("body{}", File(root, "out/site/css/a.css").readText())
    }

    @Test
    fun copyMoveDeleteAndGuards() {
        val (root, w) = ws()
        File(root, "a").mkdirs(); File(root, "b").mkdirs()
        File(root, "a/f.txt").writeText("x")
        val ops = com.codepress.app.agent.FileOps(w)
        ops.copy(listOf("a"), "b")
        assertTrue(File(root, "b/a/f.txt").exists() && File(root, "a/f.txt").exists())
        ops.move(listOf("a/f.txt"), "b")
        assertTrue(File(root, "b/f.txt").exists() && !File(root, "a/f.txt").exists())
        try { ops.move(listOf("b"), "b/a"); org.junit.Assert.fail() } catch (e: IllegalArgumentException) { }
        assertEquals(2, ops.delete(listOf("b")))
        assertTrue(!File(root, "b").exists())
    }

    @Test
    fun commandGuardBlocksEscapes() {
        val (root, _) = ws()
        val g = com.codepress.app.agent.CommandGuard
        assertTrue(g.check("rm -rf /", root, root) != null)
        assertTrue(g.check("cd ../..", root, root) != null)
        assertTrue(g.check("cat /data/data/x/shared_prefs/a.xml", root, root) != null)
        assertTrue(g.check("mkdir -p src/components && ls src/..", root, root) == null)
        assertTrue(g.check("sed -n '/foo/p' file.txt", root, root) == null)
    }

    @Test
    fun builtinUnzipAndMkdirWorkInShell() = kotlinx.coroutines.runBlocking {
        val (root, w) = ws()
        val shell = ShellRunner(w)
        File(root, "p").mkdirs(); File(root, "p/a.txt").writeText("hey")
        shell.run("zip -r p.zip p", ShellOrigin.AI)
        val e = shell.run("unzip p.zip -d outdir", ShellOrigin.AI)
        assertEquals(ShellState.COMPLETED, e.state)
        assertEquals("hey", File(root, "outdir/p/a.txt").readText())
        val bad = shell.run("rm -rf /", ShellOrigin.AI)
        assertEquals(ShellState.FAILED, bad.state)
        assertTrue(bad.output.startsWith("Blocked"))
    }
}

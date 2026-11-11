package com.codepress.app.data

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class LinuxEnvTest {
    @Test fun prootCommandHasExpectedMountsAndCommand() {
        val args = ProotCommandBuilder.build("/native/libproot.so", "/files/alpine", "/files/work", "/work/src", "git status")
        assertEquals("/native/libproot.so", args.first())
        assertTrue(args.containsAll(listOf("-r", "/files/alpine", "-b", "/files/work:/work", "-w", "/work/src", "/bin/sh", "-l", "-c", "git status")))
        assertFalse(args.contains("/system/bin/sh"))
    }

    @Test fun tarExtractionKeepsRelativeSymlink() {
        val dir = Files.createTempDirectory("alpine-tar-test").toFile()
        try {
            val archive = File(dir, "ok.tar.gz")
            writeTar(archive) {
                addFile("usr/bin/tool", "#!/bin/sh\necho ok\n")
                val link = TarArchiveEntry("bin/tool", TarConstants.LF_SYMLINK)
                link.linkName = "../usr/bin/tool"
                putArchiveEntry(link); closeArchiveEntry()
            }
            val root = File(dir, "root")
            SafeTarExtractor.extractGzip(archive, root)
            assertTrue(File(root, "usr/bin/tool").isFile)
            assertTrue(Files.isSymbolicLink(File(root, "bin/tool").toPath()))
            assertEquals("../usr/bin/tool", Files.readSymbolicLink(File(root, "bin/tool").toPath()).toString())
        } finally { dir.deleteRecursively() }
    }

    @Test fun tarExtractionAcceptsDotRootEntries() {
        val dir = Files.createTempDirectory("alpine-tar-test").toFile()
        try {
            val archive = File(dir, "dot-root.tar.gz")
            writeTar(archive) {
                val rootEntry = TarArchiveEntry("./", TarConstants.LF_DIR)
                putArchiveEntry(rootEntry); closeArchiveEntry()
                addFile("./etc/alpine-release", "3.22.0")
            }
            val root = File(dir, "rootfs")
            SafeTarExtractor.extractGzip(archive, root)
            assertEquals("3.22.0", File(root, "etc/alpine-release").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun tarExtractionRejectsEscapingSymlink() {
        val dir = Files.createTempDirectory("alpine-tar-test").toFile()
        try {
            val archive = File(dir, "bad-link.tar.gz")
            writeTar(archive) {
                val link = TarArchiveEntry("root/escape", TarConstants.LF_SYMLINK)
                link.linkName = "../../../../outside"
                putArchiveEntry(link); closeArchiveEntry()
            }
            var failed = false
            try { SafeTarExtractor.extractGzip(archive, File(dir, "rootfs")) } catch (_: IOException) { failed = true }
            assertTrue(failed)
            assertFalse(File(dir, "outside").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun tarExtractionRejectsTraversal() {
        val dir = Files.createTempDirectory("alpine-tar-test").toFile()
        try {
            val archive = File(dir, "bad.tar.gz")
            writeTar(archive) { addFile("../outside.txt", "bad") }
            val root = File(dir, "root")
            var failed = false
            try { SafeTarExtractor.extractGzip(archive, root) } catch (_: IOException) { failed = true }
            assertTrue(failed)
            assertFalse(File(dir, "outside.txt").exists())
        } finally { dir.deleteRecursively() }
    }

    private fun writeTar(file: File, build: TarArchiveOutputStream.() -> Unit) {
        GZIPOutputStream(file.outputStream()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.build()
                tar.finish()
            }
        }
    }

    private fun TarArchiveOutputStream.addFile(name: String, text: String) {
        val bytes = text.toByteArray()
        val entry = TarArchiveEntry(name).apply { size = bytes.size.toLong(); mode = 0b110100100 }
        putArchiveEntry(entry); write(bytes); closeArchiveEntry()
    }
}

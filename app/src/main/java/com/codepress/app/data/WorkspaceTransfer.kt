package com.codepress.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.codepress.app.agent.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Moves files between the phone's storage and the agent's private workspace. */
class WorkspaceTransfer(private val context: Context, private val workspace: Workspace) {

    /** Copies a folder the user picked into the workspace. Returns the number of files copied. */
    suspend fun importFolder(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Cannot open that folder")
        val target = uniqueDir(root.name ?: "imported")
        var count = 0
        fun walk(src: DocumentFile, dst: File) {
            dst.mkdirs()
            for (child in src.listFiles()) {
                if (count >= MAX_FILES) return
                val name = child.name?.replace('/', '_') ?: continue
                if (child.isDirectory) {
                    walk(child, File(dst, name))
                } else if (child.isFile && child.length() <= MAX_FILE_BYTES) {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        File(dst, name).outputStream().use { input.copyTo(it) }
                    }
                    count++
                }
            }
        }
        walk(root, target)
        count
    }

    suspend fun importFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (uri in uris) {
            val doc = DocumentFile.fromSingleUri(context, uri) ?: continue
            val name = (doc.name ?: "file").replace('/', '_')
            context.contentResolver.openInputStream(uri)?.use { input ->
                File(workspace.root, name).outputStream().use { input.copyTo(it) }
                count++
            }
        }
        count
    }

    suspend fun exportZip(target: Uri): Int = withContext(Dispatchers.IO) {
        var count = 0
        val out = context.contentResolver.openOutputStream(target) ?: throw IOException("Cannot write the zip file")
        ZipOutputStream(out.buffered()).use { zip ->
            for (rel in workspace.listFiles(limit = 20_000)) {
                val file = File(workspace.root, rel)
                zip.putNextEntry(ZipEntry(rel))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        count
    }

    private fun uniqueDir(name: String): File {
        val base = name.replace('/', '_')
        var dir = File(workspace.root, base)
        var i = 2
        while (dir.exists()) dir = File(workspace.root, "$base-$i").also { i++ }
        return dir
    }

    private companion object {
        const val MAX_FILES = 5000
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
    }
}

package com.codepress.app.agent

import java.io.File

/**
 * The directory the agent is allowed to touch with its file tools.
 * Every path coming from a model goes through [resolve], which refuses anything
 * that would leave the workspace (including via ".." or symlinks).
 */
class Workspace(root: File) {
    init {
        root.mkdirs()
    }

    val root: File = root.canonicalFile

    fun resolve(path: String): File {
        val rel = path.trim().ifEmpty { "." }.trimStart('/')
        val file = File(root, rel).canonicalFile
        val inside = file.path == root.path || file.path.startsWith(root.path + File.separator)
        require(inside) { "Path is outside the workspace: $path" }
        return file
    }

    fun relative(file: File): String =
        file.relativeTo(root).path.ifEmpty { "." }

    /** All regular files, as workspace-relative paths, sorted. Capped to keep the UI responsive. */
    fun listFiles(limit: Int = 2000): List<String> =
        root.walkTopDown()
            .filter { it.isFile }
            .take(limit)
            .map { relative(it) }
            .sorted()
            .toList()

    fun clear() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }
}

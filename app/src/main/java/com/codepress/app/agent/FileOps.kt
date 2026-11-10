package com.codepress.app.agent

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class FsEntry(val path: String, val name: String, val isDir: Boolean, val size: Long, val items: Int, val modified: Long)

data class SearchHit(val path: String, val line: Int, val text: String)

/** Folder and ZIP operations on a project workspace. Every path is checked by [Workspace.resolve]. */
class FileOps(private val ws: Workspace) {

    private val skipDirs = setOf("node_modules", ".git")

    private fun rel(f: File) = ws.relative(f).let { if (it == ".") "" else it }

    fun list(dir: String): List<FsEntry> {
        val d = ws.resolve(dir.ifEmpty { "." })
        return d.listFiles().orEmpty().map { f ->
            FsEntry(rel(f), f.name, f.isDirectory, if (f.isFile) f.length() else 0L, if (f.isDirectory) (f.list()?.size ?: 0) else 0, f.lastModified())
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    fun dirSize(path: String, cap: Int = 20000): Long =
        ws.resolve(path.ifEmpty { "." }).walkTopDown().filter { it.isFile }.take(cap).sumOf { it.length() }

    fun countFiles(path: String): Int =
        ws.resolve(path.ifEmpty { "." }).let { if (it.isFile) 1 else it.walkTopDown().count { f -> f.isFile } }

    /** Folders only, for the "choose destination" list. "" is the project root. */
    fun allDirs(limit: Int = 500): List<String> =
        listOf("") + ws.root.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isDirectory && it != ws.root }
            .take(limit).map { rel(it) }.sorted().toList()

    private fun validName(name: String): String {
        val n = name.trim()
        require(n.isNotEmpty() && n != "." && n != ".." && !n.contains('/') && !n.contains('\\')) { "Invalid name: \"$name\"" }
        return n
    }

    fun mkdir(parent: String, name: String): String {
        val d = File(ws.resolve(parent.ifEmpty { "." }), validName(name))
        ws.resolve(rel(d).ifEmpty { "." })
        require(!d.exists()) { "\"${d.name}\" already exists" }
        require(d.mkdirs()) { "Could not create the folder" }
        return rel(d)
    }

    fun createFile(parent: String, name: String): String {
        val f = File(ws.resolve(parent.ifEmpty { "." }), validName(name))
        require(!f.exists()) { "\"${f.name}\" already exists" }
        f.parentFile?.mkdirs()
        require(f.createNewFile()) { "Could not create the file" }
        return rel(f)
    }

    fun rename(path: String, newName: String): String {
        val src = ws.resolve(path)
        require(src != ws.root) { "Cannot rename the project root" }
        val dst = File(src.parentFile, validName(newName))
        require(!dst.exists()) { "\"${dst.name}\" already exists" }
        require(src.renameTo(dst)) { "Could not rename" }
        return rel(dst)
    }

    /** Deletes files or folders. Returns how many files were removed. */
    fun delete(paths: List<String>): Int {
        var n = 0
        for (p in paths) {
            val f = ws.resolve(p)
            require(f != ws.root) { "Cannot delete the project root" }
            n += countFiles(p)
            require(f.deleteRecursively()) { "Could not delete ${f.name}" }
        }
        return n
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = if (File(dir, name).isDirectory || !name.contains('.')) name else name.substringBeforeLast('.')
        val ext = if (!name.contains('.') || File(dir, name).isDirectory) "" else "." + name.substringAfterLast('.')
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base copy${if (i > 1) " $i" else ""}$ext")
            i++
        }
        return f
    }

    private fun guardInto(src: File, destDir: File) {
        require(destDir.isDirectory) { "Destination is not a folder" }
        require(!(src.isDirectory && (destDir.canonicalPath == src.canonicalPath || destDir.canonicalPath.startsWith(src.canonicalPath + File.separator)))) {
            "Cannot put \"${src.name}\" inside itself"
        }
    }

    fun copy(paths: List<String>, destDir: String): Int {
        val dest = ws.resolve(destDir.ifEmpty { "." })
        var n = 0
        for (p in paths) {
            val src = ws.resolve(p)
            require(src != ws.root) { "Cannot copy the project root" }
            guardInto(src, dest)
            val target = uniqueTarget(dest, src.name)
            if (src.isDirectory) require(src.copyRecursively(target, overwrite = false)) { "Could not copy ${src.name}" } else src.copyTo(target)
            n++
        }
        return n
    }

    fun move(paths: List<String>, destDir: String): Int {
        val dest = ws.resolve(destDir.ifEmpty { "." })
        var n = 0
        for (p in paths) {
            val src = ws.resolve(p)
            require(src != ws.root) { "Cannot move the project root" }
            guardInto(src, dest)
            if (src.parentFile?.canonicalPath == dest.canonicalPath) continue
            val target = uniqueTarget(dest, src.name)
            if (!src.renameTo(target)) {
                if (src.isDirectory) src.copyRecursively(target, overwrite = false) else src.copyTo(target)
                src.deleteRecursively()
            }
            n++
        }
        return n
    }

    // ------------------------------------------------------------------ ZIP

    /** Writes the given files/folders into one ZIP stream. Returns the number of entries. */
    fun zipTo(paths: List<String>, out: OutputStream): Int {
        var n = 0
        ZipOutputStream(out.buffered()).use { zip ->
            for (p in paths) {
                val top = ws.resolve(p.ifEmpty { "." })
                val base = if (top == ws.root) top else top.parentFile
                val files = if (top.isFile) sequenceOf(top) else top.walkTopDown().onEnter { it.name !in skipDirs }
                for (f in files) {
                    val name = f.relativeTo(base).path.replace(File.separatorChar, '/')
                    if (name.isEmpty()) continue
                    if (f.isDirectory) {
                        if (f.list().isNullOrEmpty()) { zip.putNextEntry(ZipEntry("$name/")); zip.closeEntry(); n++ }
                    } else {
                        zip.putNextEntry(ZipEntry(name))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        n++
                    }
                }
            }
        }
        return n
    }

    fun zipToFile(outPath: String, inputs: List<String>): Int {
        val out = ws.resolve(outPath)
        out.parentFile?.mkdirs()
        val others = inputs.filter { ws.resolve(it) != out }
        return out.outputStream().use { zipTo(others, it) }
    }

    fun zipEntries(zipPath: String): List<Pair<String, Long>> =
        ZipFile(ws.resolve(zipPath)).use { z -> z.entries().asSequence().take(1000).map { it.name to it.size }.toList() }

    /** Extracts a ZIP that is inside the workspace into [destDir] (workspace-relative). Returns the number of files. */
    fun unzip(zipPath: String, destDir: String): Int {
        val zip = ws.resolve(zipPath)
        require(zip.isFile) { "No such file: $zipPath" }
        val dest = ws.resolve(destDir.ifEmpty { "." })
        return extract(zip, dest, ws.root)
    }

    private fun extract(zip: File, dest: File, guardRoot: File): Int {
        dest.mkdirs()
        val destCanon = dest.canonicalFile
        val guardCanon = guardRoot.canonicalFile
        var files = 0
        var bytes = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                require(files + 1 <= 20000) { "ZIP has too many files" }
                val target = File(destCanon, e.name).canonicalFile
                val inside = target.path == destCanon.path || target.path.startsWith(destCanon.path + File.separator)
                val inWorkspace = target.path.startsWith(guardCanon.path + File.separator) || target.path == guardCanon.path
                require(inside && inWorkspace) { "Blocked unsafe path in ZIP: ${e.name}" }
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = zin.read(buf)
                            if (r < 0) break
                            bytes += r
                            require(bytes <= 1_500_000_000L) { "ZIP is too large to extract" }
                            out.write(buf, 0, r)
                        }
                    }
                    files++
                }
            }
        }
        return files
    }

    /** Extracts a ZIP that lives anywhere on disk into another workspace (used by "import as new project"). */
    fun unzipExternal(zip: File, intoRoot: File): Int = extract(zip, intoRoot, intoRoot)

    // ------------------------------------------------------ detection / search

    fun detectProject(): List<String> {
        fun has(n: String) = File(ws.root, n).exists()
        val found = linkedSetOf<String>()
        if (has("package.json")) found += "Node.js"
        if (has("requirements.txt") || has("pyproject.toml") || ws.root.listFiles { f -> f.extension == "py" }?.isNotEmpty() == true) found += "Python"
        if (has("pom.xml")) found += "Maven (Java)"
        if (has("build.gradle") || has("build.gradle.kts") || has("settings.gradle") || has("settings.gradle.kts")) found += "Gradle"
        if (has("Cargo.toml")) found += "Rust"
        if (has("go.mod")) found += "Go"
        if (has("composer.json")) found += "PHP"
        if (has("pubspec.yaml")) found += "Flutter/Dart"
        if (has("CMakeLists.txt") || has("Makefile")) found += "C/C++"
        if (has("index.html")) found += "Web (HTML)"
        return found.toList()
    }

    fun search(query: String, content: Boolean, limit: Int = 100): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<SearchHit>()
        for (f in ws.root.walkTopDown().onEnter { it.name !in skipDirs }) {
            if (out.size >= limit) break
            if (f == ws.root) continue
            val r = rel(f)
            if (f.name.contains(q, ignoreCase = true)) out += SearchHit(r, 0, if (f.isDirectory) "folder" else "file name")
            if (content && f.isFile && f.length() in 1..1_000_000) {
                val binary = f.inputStream().use { s -> val b = ByteArray(2000); val n = s.read(b); (0 until maxOf(n, 0)).any { b[it].toInt() == 0 } }
                if (binary) continue
                f.useLines { lines ->
                    for ((i, line) in lines.withIndex()) {
                        if (line.contains(q, ignoreCase = true)) {
                            out += SearchHit(r, i + 1, line.trim().take(200))
                            if (out.size >= limit) break
                        }
                    }
                }
            }
        }
        return out
    }
}

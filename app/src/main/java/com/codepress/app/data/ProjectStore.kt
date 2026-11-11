package com.codepress.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Project(val id: String, val name: String, val createdAt: Long, val updatedAt: Long, val folder: String = id)

/**
 * Every project lives in its own folder:
 *   files/projects/<project name>/workspace   - the project's files
 *   files/projects/<project name>/chat.json   - the project's chat history
 * so chats and files of different projects never mix.
 */
class ProjectStore(private val context: Context) {

    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val indexFile = File(root, "projects.json")

    @Synchronized
    fun list(): List<Project> = read().sortedByDescending { it.updatedAt }

    @Synchronized
    fun create(name: String): Project {
        val now = System.currentTimeMillis()
        val existing = read()
        val cleaned = clean(name)
        val p = Project(UUID.randomUUID().toString().take(8), cleaned, now, now, uniqueFolder(cleaned, existing))
        write(existing + p)
        workspaceDir(p.id)
        return p
    }

    @Synchronized
    fun rename(id: String, name: String) {
        write(read().map { if (it.id == id) it.copy(name = clean(name)) else it })
    }

    @Synchronized
    fun touch(id: String) {
        val now = System.currentTimeMillis()
        write(read().map { if (it.id == id) it.copy(updatedAt = now) else it })
    }

    @Synchronized
    fun delete(id: String) {
        val d = dir(id)
        write(read().filterNot { it.id == id })
        d.deleteRecursively()
    }

    fun dir(id: String) = File(root, read().firstOrNull { it.id == id }?.folder ?: id)

    private fun folderName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_").trim().trim('.').ifBlank { "Untitled" }

    private fun uniqueFolder(name: String, all: List<Project>): String {
        val base = folderName(name)
        val taken = all.map { it.folder.lowercase() }.toSet()
        if (base.lowercase() !in taken && !File(root, base).exists()) return base
        var n = 2
        while ("$base ($n)".lowercase() in taken || File(root, "$base ($n)").exists()) n++
        return "$base ($n)"
    }

    /** Give existing projects (folders named by random id) a folder named after the project. */
    @Synchronized
    fun migrateFolderNames() {
        var list = read()
        var changed = false
        list.forEach { p ->
            if (p.folder != p.id) return@forEach
            val old = File(root, p.folder)
            if (!old.isDirectory) return@forEach
            val target = uniqueFolder(p.name, list.filter { it.id != p.id })
            if (old.renameTo(File(root, target))) {
                list = list.map { if (it.id == p.id) it.copy(folder = target) else it }
                changed = true
            }
        }
        if (changed) write(list)
    }

    fun workspaceDir(id: String): File = File(dir(id), "workspace").also { it.mkdirs() }

    @Synchronized
    fun saveChat(id: String, json: String) {
        val d = dir(id)
        if (!d.isDirectory) return
        val tmp = File(d, "chat.json.tmp")
        tmp.writeText(json)
        val target = File(d, "chat.json")
        if (!tmp.renameTo(target)) {
            target.writeText(json)
            tmp.delete()
        }
        touch(id)
    }

    @Synchronized
    fun loadChat(id: String): String? = File(dir(id), "chat.json").takeIf { it.isFile }?.readText()

    /** Older versions kept one shared workspace. Move it into a first project so nothing is lost. */
    @Synchronized
    fun migrateLegacy() {
        if (indexFile.exists()) return
        val legacy = File(context.filesDir, "workspace")
        if (legacy.isDirectory && legacy.walkTopDown().any { it.isFile }) {
            val p = create("My first project")
            val dest = File(dir(p.id), "workspace")
            dest.deleteRecursively()
            if (!legacy.renameTo(dest)) {
                legacy.copyRecursively(dest, true)
                legacy.deleteRecursively()
            }
        }
        if (!indexFile.exists()) write(emptyList())
    }

    private fun clean(name: String) = name.trim().replace('\n', ' ').take(40).ifBlank { "Untitled" }

    private fun read(): List<Project> = try {
        val arr = JSONArray(indexFile.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Project(o.getString("id"), o.getString("name"), o.optLong("createdAt"), o.optLong("updatedAt"), o.optString("folder", o.getString("id")))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(list: List<Project>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt).put("folder", it.folder))
        }
        val tmp = File(root, "projects.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(arr.toString())
            tmp.delete()
        }
    }
}

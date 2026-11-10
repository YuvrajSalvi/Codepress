package com.codepress.app.data

import android.content.Context
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Project(val id: String, val name: String, val createdAt: Long, val updatedAt: Long)

/**
 * Every project lives in its own folder:
 *   files/projects/<id>/workspace   - the project's files
 *   files/projects/<id>/chat.json   - the project's chat history
 * so chats and files of different projects never mix.
 *
 * With "All files access" the same layout lives in the visible root folder /storage/emulated/0/CodePress
 * (created automatically); without it the app falls back to its private storage.
 */
class ProjectStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("codepress_storage", Context.MODE_PRIVATE)
    private val internalRoot = File(context.filesDir, "projects")
    @Volatile private var root: File = resolveRoot().apply { mkdirs() }
    private val indexFile: File get() = File(root, "projects.json")

    /** True when the user granted All files access, so the public CodePress folder can be used. */
    fun hasSharedAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    val isUsingSharedRoot: Boolean get() = root.absolutePath != internalRoot.absolutePath

    private fun sharedRoot() = File(Environment.getExternalStorageDirectory(), "CodePress")

    private fun resolveRoot(): File {
        if (hasSharedAccess()) {
            val shared = sharedRoot()
            if (shared.isDirectory || shared.mkdirs()) return shared
        }
        return internalRoot
    }

    /** Called after the permission screen: creates /CodePress and copies existing projects into it. */
    @Synchronized
    fun adoptSharedRoot(): Boolean {
        if (isUsingSharedRoot || !hasSharedAccess()) return false
        val shared = sharedRoot()
        if (!shared.isDirectory && !shared.mkdirs()) return false
        runCatching { if (internalRoot.isDirectory) internalRoot.copyRecursively(shared, overwrite = false) }
        root = shared
        return true
    }

    /** Recreates a project (id preserved) from a cloud backup if it does not exist locally yet. */
    @Synchronized
    fun restore(id: String, name: String, chatJson: String) {
        if (read().none { it.id == id }) {
            val now = System.currentTimeMillis()
            write(read() + Project(id, clean(name), now, now))
            workspaceDir(id)
        }
        val chat = File(dir(id), "chat.json")
        if (!chat.exists()) chat.writeText(chatJson)
    }

    @Synchronized
    fun list(): List<Project> = read().sortedByDescending { it.updatedAt }

    @Synchronized
    fun create(name: String): Project {
        val now = System.currentTimeMillis()
        val p = Project(UUID.randomUUID().toString().take(8), clean(name), now, now)
        write(read() + p)
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
        write(read().filterNot { it.id == id })
        dir(id).deleteRecursively()
    }

    fun dir(id: String) = File(root, id)

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
            Project(o.getString("id"), o.getString("name"), o.optLong("createdAt"), o.optLong("updatedAt"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(list: List<Project>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt))
        }
        val tmp = File(root, "projects.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(arr.toString())
            tmp.delete()
        }
    }
}

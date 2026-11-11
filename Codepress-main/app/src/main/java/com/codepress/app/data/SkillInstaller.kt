package com.codepress.app.data

import com.codepress.app.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** A GitHub repository that can be installed as a skill. */
data class SkillRepo(val owner: String, val repo: String, val ref: String? = null) {
    /** Folder name inside the project's skills/ folder. */
    val folder: String get() = repo
    /** Name shown to the user: "html-game-skill" becomes "html game skill". Never the link. */
    val title: String get() = prettyName(repo)
    val url: String get() = "https://github.com/$owner/$repo.git"
}

fun prettyName(repo: String): String = repo.removeSuffix(".git").replace('-', ' ').replace('_', ' ').trim()

/** The skills that ship in the library. The links are kept here and are never shown in the app, only the names. */
val BUILT_IN_SKILLS: List<SkillRepo> = listOf(
    "https://github.com/YuvrajSalvi/imgbb.js.git",
    "https://github.com/morluto/rea.git",
    "https://github.com/VoltAgent/awesome-design-md.git",
    "https://github.com/tashfeenahmed/freellmapi.git",
    "https://github.com/kawaicutout/html-game-skill.git",
).mapNotNull { parseSkillRepo(it) }

/** Reads https://github.com/owner/repo(.git)(/tree/branch), git@github.com:owner/repo.git or owner/repo. */
fun parseSkillRepo(input: String): SkillRepo? {
    val t = input.trim().trimEnd('/')
    val m = Regex("""^(?:https?://(?:www\.)?github\.com/|git@github\.com:|ssh://git@github\.com/)?([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]+?)(?:\.git)?(?:/tree/([^/\s]+))?$""").matchEntire(t) ?: return null
    val (owner, repo, ref) = m.destructured
    if (repo.isBlank() || repo == "." || repo == "..") return null
    return SkillRepo(owner, repo, ref.ifBlank { null })
}

/**
 * Downloads a repository as a ZIP over HTTPS (no git needed) and unzips it into <project>/skills/<repo name>/.
 * Public repositories need no login. A private repository works when the GitHub token from Settings → Connectors is saved.
 */
class SkillInstaller(private val githubToken: () -> String) {

    private val maxBytes = 60L * 1024 * 1024
    private val maxFiles = 5000

    fun installedDir(workspaceRoot: File, skill: SkillRepo) = File(File(workspaceRoot, "skills"), skill.folder)

    fun isInstalled(workspaceRoot: File, skill: SkillRepo) = installedDir(workspaceRoot, skill).isDirectory

    fun installedNames(workspaceRoot: File): List<String> =
        File(workspaceRoot, "skills").listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.map { it.name }?.sorted().orEmpty()

    fun remove(workspaceRoot: File, skill: SkillRepo) {
        installedDir(workspaceRoot, skill).deleteRecursively()
    }

    suspend fun install(workspaceRoot: File, skill: SkillRepo, status: (String) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val skillsRoot = File(workspaceRoot, "skills").apply { mkdirs() }
        val tmpZip = File(skillsRoot, ".download-${skill.repo}.zip")
        val staging = File(skillsRoot, ".staging-${skill.repo}")
        try {
            status("Downloading ${skill.title}")
            download(skill, tmpZip)
            status("Unzipping ${skill.title}")
            staging.deleteRecursively()
            staging.mkdirs()
            val count = unzipStripTop(tmpZip, staging)
            if (count == 0) throw IOException("The repository is empty.")
            val target = installedDir(workspaceRoot, skill)
            target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
            count
        } finally {
            tmpZip.delete()
            staging.deleteRecursively()
        }
    }

    private suspend fun download(skill: SkillRepo, into: File) {
        val path = if (skill.ref != null) "zipball/${skill.ref}" else "zipball"
        val url = "https://api.github.com/repos/${skill.owner}/${skill.repo}/$path"
        val token = githubToken().trim()
        // First with the token (needed for private repositories); if GitHub refuses a wrong token, try again without it.
        val attempts = if (token.isNotEmpty()) listOf(token, "") else listOf("")
        var last: IOException? = null
        for (t in attempts) {
            coroutineContext.ensureActive()
            val builder = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "CodePress")
            if (t.isNotEmpty()) builder.header("Authorization", "Bearer $t")
            try {
                Http.client.newCall(builder.build()).execute().use { r ->
                    if (!r.isSuccessful) {
                        throw IOException(
                            when (r.code) {
                                401 -> "GitHub does not accept the saved token."
                                403 -> "GitHub refused the download (rate limit?). Try again in a few minutes."
                                404 -> "Repository not found. Check the link, or save a GitHub token in Connectors if it is private."
                                else -> "GitHub answered with error ${r.code}."
                            },
                        )
                    }
                    val body = r.body ?: throw IOException("Empty answer from GitHub.")
                    var total = 0L
                    body.byteStream().use { input ->
                        into.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > maxBytes) throw IOException("The repository is larger than 60 MB.")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
                return
            } catch (e: IOException) {
                last = e
                if (e.message?.startsWith("GitHub does not accept") != true) break
            }
        }
        throw last ?: IOException("Download failed.")
    }

    /** GitHub zips contain one top folder (owner-repo-sha/). Unzip without it, and refuse paths that leave the target. */
    private suspend fun unzipStripTop(zip: File, target: File): Int {
        val base = target.canonicalPath + File.separator
        var files = 0
        var written = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zin.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                val rel = name.substringAfter('/', "")
                if (rel.isBlank()) continue
                val out = File(target, rel)
                if (!out.canonicalPath.startsWith(base)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                if (++files > maxFiles) throw IOException("The repository has too many files.")
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > maxBytes * 2) throw IOException("The repository is too large once unzipped.")
                        o.write(buf, 0, n)
                    }
                }
            }
        }
        return files
    }
}

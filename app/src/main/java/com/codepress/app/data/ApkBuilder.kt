package com.codepress.app.data

import com.codepress.app.agent.AppJson
import com.codepress.app.agent.Workspace
import com.codepress.app.agent.parseObject
import com.codepress.app.agent.str
import com.codepress.app.llm.Http
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Builds an Android project into an installable APK.
 *
 * A phone has no Android SDK, so the build runs on GitHub Actions in a private repository
 * that belongs to the user (`codepress-builds`): the project files are uploaded as a temporary branch,
 * the workflow runs `gradle assembleDebug`, the APK is downloaded into the project's `apk/` folder
 * (visible in the Files tab) and the temporary branch is deleted again.
 * Uses the GitHub token from Settings → Plugins (it needs the `repo` and `workflow` permissions).
 */
class ApkBuilder(private val user: String, private val token: String) {

    private val api = "https://api.github.com"

    suspend fun build(workspace: Workspace, status: (String) -> Unit): String {
        if (user.isBlank() || token.isBlank()) {
            throw IOException("GitHub is not connected. Open Settings → Plugins → GitHub, enter your username and a token with the “repo” and “workflow” permissions, then try again.")
        }
        val projectFiles = collect(workspace)
        if (projectFiles.none { it.rel == "settings.gradle.kts" || it.rel == "settings.gradle" }) {
            throw IOException("No Gradle project found. An Android project needs settings.gradle(.kts) in the project root and an app module with build.gradle(.kts) and src/main/AndroidManifest.xml.")
        }

        status("Preparing the build repository")
        ensureRepo()

        status("Uploading ${projectFiles.size} files")
        val branch = "cp-build-${System.currentTimeMillis()}"
        val commit = createCommit(projectFiles)
        post("/repos/$user/$REPO/git/refs", buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("sha", commit)
        })

        try {
            status("Waiting for the build to start")
            val run = waitForRun(branch)
            val runId = run.str("id") ?: throw IOException("GitHub did not report a build id.")
            val finished = waitForFinish(runId, status)
            val conclusion = finished.str("conclusion")
            if (conclusion != "success") {
                throw IOException("The build failed. Fix these errors and call build_apk again:\n" + failureSummary(runId))
            }
            status("Downloading the APK")
            return download(runId, workspace)
        } finally {
            runCatching { call("DELETE", "/repos/$user/$REPO/git/refs/heads/$branch", null) }
        }
    }

    // ---------------------------------------------------------------- files

    private class Entry(val rel: String, val file: File)

    private fun collect(ws: Workspace): List<Entry> {
        val skipDirs = setOf(".git", ".gradle", "build", ".codepress", "apk", "node_modules", ".idea", ".cxx")
        val out = mutableListOf<Entry>()
        ws.root.walkTopDown()
            .onEnter { it == ws.root || it.name !in skipDirs }
            .filter { it.isFile && it.length() <= MAX_FILE }
            .forEach { out += Entry(ws.relative(it).replace(File.separatorChar, '/'), it) }
        return out
    }

    private fun asText(bytes: ByteArray): String? {
        if (bytes.any { it.toInt() == 0 }) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun createCommit(files: List<Entry>): String {
        val tree = buildJsonArray {
            for (f in files) {
                val bytes = f.file.readBytes()
                val text = asText(bytes)
                add(buildJsonObject {
                    put("path", f.rel)
                    put("mode", if (f.rel == "gradlew") "100755" else "100644")
                    put("type", "blob")
                    if (text != null) put("content", text) else {
                        val blob = post("/repos/$user/$REPO/git/blobs", buildJsonObject {
                            put("content", Base64.getEncoder().encodeToString(bytes))
                            put("encoding", "base64")
                        })
                        put("sha", blob.str("sha") ?: throw IOException("GitHub rejected a file upload."))
                    }
                })
            }
            add(buildJsonObject {
                put("path", ".github/workflows/codepress-build.yml")
                put("mode", "100644")
                put("type", "blob")
                put("content", WORKFLOW)
            })
        }
        val t = post("/repos/$user/$REPO/git/trees", buildJsonObject { put("tree", tree) })
        val c = post("/repos/$user/$REPO/git/commits", buildJsonObject {
            put("message", "CodePress build")
            put("tree", t.str("sha") ?: throw IOException("GitHub did not create the file tree."))
            put("parents", JsonArray(emptyList()))
        })
        return c.str("sha") ?: throw IOException("GitHub did not create the commit.")
    }

    // ---------------------------------------------------------------- GitHub

    private fun ensureRepo() {
        val r = call("GET", "/repos/$user/$REPO", null)
        if (r.first == 200) return
        if (r.first != 404) throw IOException(explain(r.first, r.second))
        post("/user/repos", buildJsonObject {
            put("name", REPO)
            put("private", true)
            put("description", "Temporary build branches created by CodePress. Safe to delete.")
            put("auto_init", true)
        })
    }

    private suspend fun waitForRun(branch: String): JsonObject {
        repeat(30) {
            coroutineContext.ensureActive()
            val r = get("/repos/$user/$REPO/actions/runs?branch=$branch&per_page=5")
            val run = r["workflow_runs"]?.jsonArray?.firstOrNull()?.jsonObject
            if (run != null) return run
            delay(3000)
        }
        throw IOException("GitHub did not start the build. Check that Actions are enabled for $user/$REPO (repository → Settings → Actions) and that the token has the “workflow” permission.")
    }

    private suspend fun waitForFinish(runId: String, status: (String) -> Unit): JsonObject {
        val started = System.currentTimeMillis()
        while (true) {
            coroutineContext.ensureActive()
            val run = get("/repos/$user/$REPO/actions/runs/$runId")
            if (run.str("status") == "completed") return run
            val min = (System.currentTimeMillis() - started) / 60_000
            status("Building the APK in the cloud (${min} min)")
            if (System.currentTimeMillis() - started > BUILD_TIMEOUT_MS) throw IOException("The build took longer than 40 minutes and was given up.")
            delay(6000)
        }
    }

    private fun failureSummary(runId: String): String {
        val bytes = try {
            raw("/repos/$user/$REPO/actions/runs/$runId/logs")
        } catch (e: Exception) {
            return "(could not download the build log: ${e.message})"
        }
        val lines = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                zip.bufferedReader().readLines().forEach { l ->
                    val clean = l.replace(Regex("""^\d{4}-\d\d-\d\dT[\d:.]+Z\s*"""), "")
                    if (Regex("""^e: |error:|What went wrong|Execution failed|Unresolved reference|FAILURE:""").containsMatchIn(clean)) lines += clean
                }
            }
        }
        return lines.distinct().take(40).joinToString("\n").ifEmpty { "(no error lines found in the log)" }
    }

    private fun download(runId: String, ws: Workspace): String {
        val arts = get("/repos/$user/$REPO/actions/runs/$runId/artifacts")["artifacts"]?.jsonArray.orEmpty()
        val art = arts.map { it.jsonObject }.firstOrNull { it.str("name") == "apk" }
            ?: throw IOException("The build finished but produced no APK file.")
        val id = (art["id"] as? JsonPrimitive)?.content ?: throw IOException("The APK artifact has no id.")
        val zip = raw("/repos/$user/$REPO/actions/artifacts/$id/zip")
        val saved = mutableListOf<String>()
        val dir = ws.resolve("apk").apply { mkdirs() }
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !e.name.endsWith(".apk")) continue
                val name = e.name.substringAfterLast('/')
                File(dir, name).outputStream().use { o -> z.copyTo(o) }
                saved += "apk/$name"
            }
        }
        if (saved.isEmpty()) throw IOException("The downloaded build contained no .apk file.")
        return "Built successfully. APK saved to the Files tab: ${saved.joinToString(", ")}. Open it from Files and tap Install."
    }

    // ---------------------------------------------------------------- HTTP

    private fun request(method: String, path: String, body: JsonElement?): Request =
        Request.Builder()
            .url(if (path.startsWith("http")) path else api + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, body?.let { Http.jsonBody(AppJson.encodeToString(JsonElement.serializer(), it)) } ?: if (method == "GET" || method == "DELETE") null else Http.jsonBody("{}"))
            .build()

    private fun call(method: String, path: String, body: JsonElement?): Pair<Int, String> =
        Http.client.newCall(request(method, path, body)).execute().use { it.code to it.body?.string().orEmpty() }

    private fun get(path: String): JsonObject {
        val (code, text) = call("GET", path, null)
        if (code !in 200..299) throw IOException(explain(code, text))
        return parseObject(text) ?: throw IOException("Unexpected answer from GitHub.")
    }

    private fun post(path: String, body: JsonElement): JsonObject {
        val (code, text) = call("POST", path, body)
        if (code !in 200..299) throw IOException(explain(code, text))
        return parseObject(text) ?: throw IOException("Unexpected answer from GitHub.")
    }

    private fun raw(path: String): ByteArray =
        Http.client.newCall(request("GET", path, null)).execute().use {
            if (!it.isSuccessful) throw IOException(explain(it.code, it.body?.string().orEmpty()))
            it.body?.bytes() ?: ByteArray(0)
        }

    private fun explain(code: Int, body: String): String {
        val msg = parseObject(body)?.str("message").orEmpty()
        return when (code) {
            401 -> "GitHub rejected the token (it may be wrong or expired). Create a new one in Settings → Plugins → GitHub."
            403, 404 -> "GitHub refused the request ($code${if (msg.isNotEmpty()) ": $msg" else ""}). The token needs the “repo” and “workflow” permissions. Create a new classic token with both ticked."
            422 -> "GitHub could not accept the files ($msg)."
            else -> "GitHub error $code${if (msg.isNotEmpty()) ": $msg" else ""}"
        }
    }

    private companion object {
        const val REPO = "codepress-builds"
        const val MAX_FILE = 8L * 1024 * 1024
        const val BUILD_TIMEOUT_MS = 40L * 60 * 1000

        val WORKFLOW = """
name: CodePress build
on:
  push:
    branches: ['cp-build-**']
jobs:
  apk:
    runs-on: ubuntu-24.04
    timeout-minutes: 35
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - name: Accept Android SDK licenses
        run: yes | "${'$'}ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.14.3
      - name: Build debug APK
        shell: bash
        run: |
          if [ -f gradle/wrapper/gradle-wrapper.jar ] && [ -f gradlew ]; then
            chmod +x gradlew
            ./gradlew assembleDebug --no-daemon --stacktrace --console=plain
          else
            gradle assembleDebug --no-daemon --stacktrace --console=plain
          fi
      - uses: actions/upload-artifact@v4
        with:
          name: apk
          path: '**/build/outputs/apk/debug/*.apk'
          if-no-files-found: error
""".trimStart()
    }
}

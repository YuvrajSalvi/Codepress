package com.codepress.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.codepress.app.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Manages the GGUF model files stored in the app's private folder. */
class LocalModels(private val context: Context) {

    val dir: File = File(context.filesDir, "models").also { it.mkdirs() }

    fun list(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }

    fun file(name: String): File? = list().firstOrNull { it.name == name }

    fun delete(name: String) {
        File(dir, name).takeIf { it.parentFile == dir }?.delete()
    }

    /** Copies a model the user picked with the system file picker. */
    suspend fun importFrom(uri: Uri, onProgress: (Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        var name = "model.gguf"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n) ?: name
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        name = safeName(name)
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                part.outputStream().use { out -> copy(input, out, size, onProgress) }
            } ?: throw IOException("Could not open the selected file")
            check(isGguf(part)) { "That file is not a GGUF model." }
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { "Could not save the model file." }
        } finally {
            part.delete()
        }
        name
    }

    /** Downloads a model from a direct link (for example a Hugging Face "resolve" URL). */
    suspend fun download(url: String, onProgress: (Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val name = safeName(Uri.parse(url).lastPathSegment?.substringBefore('?') ?: "model.gguf")
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        try {
            Http.client.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build().newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code}). Check the link.")
                val body = r.body ?: throw IOException("Empty download")
                body.byteStream().use { input ->
                    part.outputStream().use { out -> copy(input, out, body.contentLength(), onProgress) }
                }
            }
            check(isGguf(part)) { "The downloaded file is not a GGUF model. Use a direct .gguf link." }
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { "Could not save the model file." }
        } finally {
            part.delete()
        }
        name
    }

    private suspend fun copy(input: java.io.InputStream, out: java.io.OutputStream, total: Long, onProgress: (Long, Long) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var lastReport = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (done - lastReport > 2_000_000) {
                onProgress(done, total)
                lastReport = done
            }
        }
        onProgress(done, total)
    }

    private fun isGguf(f: File): Boolean =
        f.length() > 1024 && f.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF"
        }

    private fun safeName(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.')
        val n = cleaned.ifEmpty { "model.gguf" }
        return if (n.endsWith(".gguf", ignoreCase = true)) n else "$n.gguf"
    }
}

package com.codepress.app.data

import android.content.Context
import android.os.Build
import com.codepress.app.llm.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Installs and launches a small Alpine Linux root filesystem under the app's private files directory. */
class LinuxEnv(private val context: Context) {
    data class Progress(val percent: Int, val message: String, val error: String? = null)

    val rootfs: File get() = File(context.filesDir, "linux/alpine")
    private val _progress = MutableStateFlow(Progress(0, "Linux is not installed"))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    val isInstalled: Boolean
        get() = File(rootfs, "bin/sh").isFile && File(rootfs, "sbin/apk").exists()

    val nativeBinariesPresent: Boolean
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so").isFile &&
            File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so").isFile

    suspend fun install() = withContext(Dispatchers.IO) {
        if (isInstalled) {
            _progress.value = Progress(100, "Alpine Linux is ready")
            return@withContext
        }
        val arch = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "x86_64"
            else -> throw IOException("This device ABI is not supported. Use arm64-v8a or x86_64.")
        }
        val base = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$arch/"
        val metadata = downloadText(base + "latest-releases.yaml")
        val archiveName = findMiniRootfsName(metadata, arch)
            ?: findMiniRootfsName(downloadText(base), arch)
            ?: throw IOException("Alpine release metadata did not contain a minirootfs for $arch.")
        val expectedHash = findHash(metadata, archiveName) ?: downloadText(base + "$archiveName.sha256")
            .trim().split(Regex("\\s+")).first()
        if (!expectedHash.matches(Regex("(?i)[0-9a-f]{64}"))) {
            throw IOException("Alpine release metadata did not provide a valid SHA-256 checksum.")
        }

        val work = File(context.cacheDir, "alpine-install").apply { deleteRecursively(); mkdirs() }
        val archive = File(work, archiveName)
        try {
            downloadFile(base + archiveName, archive)
            currentCoroutineContext().ensureActive()
            _progress.value = Progress(78, "Verifying Alpine download…")
            val actual = sha256(archive)
            if (!actual.equals(expectedHash, ignoreCase = true)) throw IOException("Alpine download checksum did not match. Please retry.")

            _progress.value = Progress(84, "Extracting Alpine Linux…")
            val staging = File(rootfs.parentFile, "alpine-staging").apply { deleteRecursively(); mkdirs() }
            try {
                val installContext = currentCoroutineContext()
                SafeTarExtractor.extractGzip(
                    archive,
                    staging,
                    checkActive = { installContext.ensureActive() },
                    onProgress = { entries, extractedBytes ->
                        // Keep the UI informed while the root filesystem is unpacked.
                        _progress.value = Progress(
                            84,
                            "Extracting Alpine Linux… $entries entries, ${extractedBytes / (1024 * 1024)} MB extracted"
                        )
                    },
                )
                _progress.value = Progress(98, "Finalizing Alpine Linux…")
                configureRootfs(staging)
                currentCoroutineContext().ensureActive()
                rootfs.parentFile?.mkdirs()
                rootfs.deleteRecursively()
                if (!staging.renameTo(rootfs)) throw IOException("Could not activate the Alpine root filesystem.")
            } catch (t: Throwable) {
                staging.deleteRecursively()
                throw t
            }
            _progress.value = Progress(100, "Alpine Linux installed")
        } finally {
            work.deleteRecursively()
        }
    }

    fun cancelProgress() { _progress.value = Progress(0, "Installation cancelled") }

    /** Command line for interactive or non-interactive execution inside Alpine. */
    fun command(shellCmd: String?, workspace: File, workingDirectory: File = workspace): List<String> {
        require(isInstalled) { "Install Alpine Linux first." }
        val native = context.applicationInfo.nativeLibraryDir
        val proot = File(native, "libproot.so")
        val loader = File(native, "libproot-loader.so")
        require(proot.isFile && loader.isFile) {
            "proot binaries are missing. Add libproot.so and libproot-loader.so under app/src/main/jniLibs/<abi>/ and rebuild."
        }
        File(context.cacheDir, "proot-tmp").mkdirs()
        val workspaceCanonical = workspace.canonicalFile
        val cwdCanonical = workingDirectory.canonicalFile
        require(cwdCanonical.path == workspaceCanonical.path || cwdCanonical.path.startsWith(workspaceCanonical.path + File.separator)) {
            "Working directory must remain inside /work."
        }
        val relative = cwdCanonical.relativeTo(workspaceCanonical).path
        val prootCwd = if (relative.isBlank() || relative == ".") "/work" else "/work/${relative.replace(File.separatorChar, '/')}"
        return ProotCommandBuilder.build(proot.absolutePath, rootfs.absolutePath, workspace.canonicalPath, prootCwd, shellCmd)
    }

    fun processEnvironment(noSeccomp: Boolean = false): Map<String, String> = buildMap {
        val native = context.applicationInfo.nativeLibraryDir
        put("PROOT_LOADER", File(native, "libproot-loader.so").absolutePath)
        val inheritedLibraryPath = System.getenv("LD_LIBRARY_PATH").orEmpty()
        put("LD_LIBRARY_PATH", listOf(native, inheritedLibraryPath).filter { it.isNotBlank() }.joinToString(File.pathSeparator))
        val tmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }.absolutePath
        put("PROOT_TMP_DIR", tmp)
        put("PROOT_TMPDIR", tmp)
        if (noSeccomp) put("PROOT_NO_SECCOMP", "1")
    }

    private suspend fun downloadText(url: String): String = withContext(Dispatchers.IO) {
        val call = Http.client.newCall(Request.Builder().url(url).build())
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { cause -> if (cause is CancellationException) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Alpine server returned HTTP ${response.code}.")
                response.body?.string() ?: throw IOException("Alpine server returned an empty response.")
            }
        } finally { handle?.dispose() }
    }

    private suspend fun downloadFile(url: String, target: File) = withContext(Dispatchers.IO) {
        val call = Http.client.newCall(Request.Builder().url(url).build())
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { cause -> if (cause is CancellationException) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Alpine download failed (HTTP ${response.code}).")
                val body = response.body ?: throw IOException("Alpine download was empty.")
                val total = body.contentLength()
                body.byteStream().use { input -> FileOutputStream(target).use { output ->
                    val buffer = ByteArray(32 * 1024); var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n); done += n
                        val pct = if (total > 0) (5 + done * 70 / total).toInt().coerceIn(5, 75) else 15
                        _progress.value = Progress(pct, "Downloading Alpine Linux… ${if (total > 0) "${done / 1024} / ${total / 1024} KB" else "${done / 1024} KB"}")
                    }
                } }
            }
        } finally { handle?.dispose() }
    }

    private fun configureRootfs(root: File) {
        File(root, "etc").mkdirs()
        File(root, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        val profile = File(root, "etc/profile.d/codepress.sh")
        profile.parentFile?.mkdirs()
        profile.writeText("export PS1='\\u@codepress:\\w\\$ '\nexport PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\nexport HOME=/root\nexport TERM=xterm-256color\n")
        File(root, "root").mkdirs()
    }

    private fun findMiniRootfsName(yaml: String, arch: String): String? {
        val regex = Regex("alpine-minirootfs-[0-9]+(?:\\.[0-9]+){2}-$arch\\.tar\\.gz")
        return regex.findAll(yaml).map { it.value }.maxWithOrNull(Comparator { a, b ->
            val av = versionKey(a); val bv = versionKey(b)
            for (i in 0 until maxOf(av.size, bv.size)) {
                val comparison = av.getOrElse(i) { 0 }.compareTo(bv.getOrElse(i) { 0 })
                if (comparison != 0) return@Comparator comparison
            }
            0
        })
    }

    private fun versionKey(name: String): List<Int> = Regex("[0-9]+")
        .findAll(name.substringAfter("alpine-minirootfs-").replace(Regex("-(?:aarch64|x86_64)\\.tar\\.gz$"), ""))
        .take(4)
        .mapNotNull { it.value.toIntOrNull() }.toList()

    private fun findHash(yaml: String, name: String): String? {
        val escaped = Regex.escape(name)
        val block = Regex("(?s).{0,300}$escaped.{0,500}").find(yaml)?.value ?: return null
        return Regex("(?i)(?:sha256|sha256sum|checksum)\\s*[:=]\\s*['\"]?([0-9a-f]{64})")
            .find(block)?.groupValues?.get(1)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val b = ByteArray(32 * 1024); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}


/** Pure JVM command-line builder, deliberately independent of Android Context. */
object ProotCommandBuilder {
    fun build(proot: String, rootfs: String, workspace: String, workingDirectory: String, shellCmd: String?): List<String> =
        buildList {
            addAll(listOf(proot, "-r", rootfs, "-0", "--link2symlink", "--kill-on-exit", "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "$workspace:/work", "-w", workingDirectory, "/bin/sh", "-l"))
            if (shellCmd != null) { add("-c"); add(shellCmd) }
        }
}

/** Tar extraction with path validation; symlinks are created only after regular files are written. */
object SafeTarExtractor {
    fun extractGzip(
        archive: File,
        destination: File,
        checkActive: () -> Unit = {},
        onProgress: (entries: Long, extractedBytes: Long) -> Unit = { _, _ -> },
    ) {
        destination.mkdirs()
        val links = mutableListOf<Pair<File, String>>()
        var entryCount = 0L
        var extractedBytes = 0L
        TarArchiveInputStream(BufferedInputStream(GZIPInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                checkActive()
                val entry = tar.nextTarEntry ?: break
                entryCount++
                if (entryCount == 1L || entryCount % 25L == 0L) onProgress(entryCount, extractedBytes)
                val output = safeTarget(destination, entry.name)
                if (entry.isSymbolicLink) {
                    validateLink(output, entry.linkName, destination)
                    links += output to entry.linkName
                    continue
                }
                if (entry.isDirectory) { output.mkdirs(); continue }
                if (entry.isFile) {
                    ensureNoSymlinkParents(output.parentFile, destination)
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { out ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            checkActive()
                            val count = tar.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            extractedBytes += count
                        }
                    }
                    if (entry.mode and 0b001001001 != 0) output.setExecutable(true, false)
                }
            }
            onProgress(entryCount, extractedBytes)
        }
        links.forEach { (link, target) ->
            checkActive()
            link.parentFile?.mkdirs()
            ensureCanonicalParentInside(link.parentFile, destination)
            FilesCompat.createSymlink(link, target)
        }
        onProgress(entryCount, extractedBytes)
    }

    private fun safeTarget(root: File, name: String): File {
        if (name.startsWith('/') || name.startsWith('\\') || name.contains('\\') || name.contains('\u0000'))
            throw IOException("Unsafe absolute tar path: $name")
        // Resolve from the canonical extraction root. Tarballs commonly start with
        // entries such as "./" and "./etc/"; these are the root itself, not traversal.
        val base = root.canonicalFile.toPath()
        val normalized = base.resolve(name).normalize()
        if (!normalized.startsWith(base)) throw IOException("Tar path escapes the install directory: $name")
        return normalized.toFile()
    }

    private fun validateLink(link: File, target: String, root: File) {
        if (target.startsWith('/') || target.startsWith('\\') || target.contains('\\') || target.contains('\u0000'))
            throw IOException("Unsafe absolute symlink target: $target")
        val resolved = link.parentFile.toPath().resolve(target).normalize()
        if (!resolved.startsWith(root.canonicalFile.toPath())) throw IOException("Symlink escapes install directory: $target")
    }

    private fun ensureCanonicalParentInside(parent: File?, root: File) {
        val canonicalRoot = root.canonicalFile.toPath()
        val canonicalParent = parent?.canonicalFile?.toPath() ?: canonicalRoot
        if (!canonicalParent.startsWith(canonicalRoot)) throw IOException("Tar symlink parent escapes install directory.")
    }

    private fun ensureNoSymlinkParents(parent: File?, root: File) {
        var cursor = parent
        while (cursor != null && cursor.toPath().normalize().startsWith(root.canonicalFile.toPath())) {
            if (java.nio.file.Files.isSymbolicLink(cursor.toPath())) throw IOException("Tar entry attempts to write through a symlink.")
            if (cursor.canonicalFile == root.canonicalFile) break
            cursor = cursor.parentFile
        }
    }

    private object FilesCompat {
        fun createSymlink(link: File, target: String) {
            try { java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target)) }
            catch (e: UnsupportedOperationException) { throw IOException("This Android version cannot create Alpine symlinks.", e) }
        }
    }
}

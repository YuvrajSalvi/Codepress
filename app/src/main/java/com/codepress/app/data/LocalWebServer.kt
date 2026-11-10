package com.codepress.app.data

import java.io.BufferedOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * A tiny static HTTP server that serves one folder (the project workspace) on the local network,
 * so a preview can be opened from a laptop or another phone at http://<phone-ip>:<port>/file.html.
 * Read-only, GET/HEAD only, and it never serves anything outside [root].
 * It lives only while the app process is alive.
 */
class LocalWebServer(private val root: File) {

    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "codepress-http").apply { isDaemon = true } }

    val port: Int get() = server?.localPort ?: -1
    val isRunning: Boolean get() = server?.isClosed == false

    /** Starts on [preferredPort]; if it is busy, falls back to any free port. */
    @Synchronized
    fun start(preferredPort: Int = 8080): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = try {
            ServerSocket(preferredPort)
        } catch (e: Exception) {
            ServerSocket(0)
        }
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                pool.execute { handle(c) }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 10_000
                val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = input.readLine() ?: return
                while (true) { // skip the headers
                    val h = input.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: return
                val target = parts.getOrNull(1) ?: "/"
                val out = BufferedOutputStream(sock.getOutputStream())
                if (method != "GET" && method != "HEAD") {
                    respond(out, 405, "text/plain; charset=utf-8", "Method not allowed".toByteArray(), method == "HEAD")
                    return
                }
                val rawPath = URLDecoder.decode(target.substringBefore('?').substringBefore('#').replace("+", "%2B"), "UTF-8")
                var file = File(root, rawPath.trimStart('/')).canonicalFile
                val inside = file.path == root.path || file.path.startsWith(root.path + File.separator)
                if (!inside) {
                    respond(out, 403, "text/plain; charset=utf-8", "Forbidden".toByteArray(), method == "HEAD")
                    return
                }
                if (file.isDirectory) {
                    val index = listOf("index.html", "index.htm").map { File(file, it) }.firstOrNull { it.isFile }
                    if (index == null) {
                        respond(out, 200, "text/html; charset=utf-8", listing(file, rawPath).toByteArray(), method == "HEAD")
                        return
                    }
                    file = index
                }
                if (!file.isFile) {
                    respond(out, 404, "text/plain; charset=utf-8", "Not found: $rawPath".toByteArray(), method == "HEAD")
                    return
                }
                val ext = file.extension.lowercase()
                if (ext == "php") {
                    respond(out, 501, "text/plain; charset=utf-8", "PHP files cannot run here: no PHP runtime is installed on this phone.".toByteArray(), method == "HEAD")
                    return
                }
                respondFile(out, file, mimeOf(ext), method == "HEAD")
            } catch (_: Exception) {
            }
        }
    }

    private fun respond(out: BufferedOutputStream, code: Int, type: String, body: ByteArray, headOnly: Boolean) {
        writeHeader(out, code, type, body.size.toLong())
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun respondFile(out: BufferedOutputStream, file: File, type: String, headOnly: Boolean) {
        writeHeader(out, 200, type, file.length())
        if (!headOnly) file.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    private fun writeHeader(out: BufferedOutputStream, code: Int, type: String, length: Long) {
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 501 -> "Not Implemented"; else -> "OK" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: $length\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun listing(dir: File, urlPath: String): String {
        val base = if (urlPath.endsWith("/")) urlPath else "$urlPath/"
        val items = dir.listFiles().orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val sb = StringBuilder("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Index of ${esc(base)}</title>")
        sb.append("<body style='font-family:sans-serif;padding:16px'><h3>Index of ${esc(base)}</h3><ul>")
        if (dir.path != root.path) sb.append("<li><a href='../'>../</a></li>")
        for (f in items) {
            val n = f.name + if (f.isDirectory) "/" else ""
            sb.append("<li><a href='").append(base).append(URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")).append(if (f.isDirectory) "/" else "").append("'>").append(esc(n)).append("</a></li>")
        }
        return sb.append("</ul></body>").toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun mimeOf(ext: String): String = when (ext) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "txt", "md" -> "text/plain; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "wasm" -> "application/wasm"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    companion object {
        /** The phone's IPv4 address on the Wi-Fi / hotspot network, or null when it is not on a LAN. */
        fun lanAddress(): String? {
            val all = try { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() } catch (e: Exception) { emptyList() }
            val candidates = all.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && it.isSiteLocalAddress }.map { nif.name to it } }
            val wifi = candidates.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("ap") || it.first.startsWith("swlan") }
            return (wifi ?: candidates.firstOrNull())?.second?.hostAddress
        }
    }
}

/** The single shared LAN preview server. Re-targets itself when a different folder is requested. */
object LanPreview {
    private var server: LocalWebServer? = null
    private var root: File? = null

    /** Starts (or re-uses) the server for [folder]; returns the http://ip:port base URL, or null when there is no LAN address. */
    @Synchronized
    fun start(folder: File): String? {
        val canon = folder.canonicalFile
        if (root != canon) {
            server?.stop()
            server = null
        }
        val s = server ?: LocalWebServer(canon).also { server = it; root = canon }
        val port = s.start()
        val ip = LocalWebServer.lanAddress() ?: return null
        return "http://$ip:$port"
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        root = null
    }

    @Synchronized
    fun isRunning(): Boolean = server?.isRunning == true
}

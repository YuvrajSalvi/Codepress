package com.codepress.app.agent

import java.io.File

/**
 * Best-effort check that a shell command stays inside the project folder.
 * It looks at every path-like word in the command. It is a safety net for the AI and for typos, not a sandbox:
 * the Android app sandbox itself is what really protects the rest of the phone.
 */
object CommandGuard {
    private val systemRoots = setOf(
        "data", "sdcard", "storage", "system", "vendor", "etc", "proc", "sys", "dev", "mnt", "root",
        "home", "usr", "bin", "sbin", "lib", "lib64", "opt", "cache", "apex", "product", "oem", "persist",
    )
    private val allowedPrefixes = listOf("/dev/null", "/dev/zero", "/dev/urandom", "/system/bin", "/system/xbin", "/proc/cpuinfo", "/proc/meminfo")

    fun check(command: String, cwd: File, root: File): String? {
        val rootPath = root.canonicalPath
        val words = command.split(Regex("""[\s;&|<>()`$"'=,]+""")).filter { it.isNotEmpty() }
        for (w in words) {
            if (w == "/" || w == "/*" || w == "/." ) return "Blocked: \"$w\" is outside the project folder."
            if (w.startsWith("/")) {
                val first = w.removePrefix("/").substringBefore('/')
                if (first in systemRoots) {
                    val ok = w.startsWith(rootPath) || allowedPrefixes.any { w.startsWith(it) }
                    if (!ok) return "Blocked: \"$w\" is outside the project folder."
                }
            }
            if (w.split('/').contains("..")) {
                val target = try { File(if (w.startsWith("/")) w else File(cwd, w).path).canonicalPath } catch (e: Exception) { null }
                if (target != null && target != rootPath && !target.startsWith(rootPath + File.separator)) {
                    return "Blocked: \"$w\" would leave the project folder."
                }
            }
        }
        return null
    }
}

package com.codepress.app.ui

import android.content.Context
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.data.LinuxEnv
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.viewinterop.AndroidView
import jackpal.androidterm.PtySession
import jackpal.androidterm.emulatorview.ColorScheme
import jackpal.androidterm.emulatorview.EmulatorView
import java.io.File

/**
 * Real interactive terminals (a shell on a pseudo-terminal, drawn by the Android-Terminal-Emulator view).
 * One session per project folder; it keeps running while the user switches tabs.
 */
object TerminalHost {
    private val sessions = HashMap<String, PtySession>()

    fun session(context: Context, root: File, linuxEnv: LinuxEnv): Result<PtySession> {
        sessions[root.path]?.let { if (it.isRunning && !it.hasExited()) return Result.success(it) }
        return runCatching {
            val home = root.apply { mkdirs() }
            val rc = File(home, ".codepress/shrc").apply {
                parentFile?.mkdirs()
                writeText("PS1='\${PWD##*/} \$ '\nalias ll='ls -la'\nalias la='ls -a'\n")
            }
            File(home, "bin").mkdirs()
            val system = "/system/bin:/system/xbin:/vendor/bin"
            val env = mapOf(
                "TERM" to "xterm-256color",
                "HOME" to home.path,
                "PWD" to home.path,
                "TMPDIR" to context.cacheDir.path,
                "PATH" to "${home.path}/bin:$system",
                "ENV" to rc.path,
                "LANG" to "en_US.UTF-8",
            )
            val created = if (linuxEnv.isInstalled && linuxEnv.nativeBinariesPresent) {
                val args = linuxEnv.command(null, home)
                val linuxEnvVars = (env - "ENV") + mapOf(
                    "HOME" to "/root",
                    "PWD" to "/work",
                    "TMPDIR" to "/tmp",
                    "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "LANG" to "C.UTF-8",
                ) + linuxEnv.processEnvironment()
                try {
                    PtySession(args.first(), args, home.path, linuxEnvVars)
                } catch (firstStart: java.io.IOException) {
                    PtySession(args.first(), args, home.path, linuxEnvVars + linuxEnv.processEnvironment(noSeccomp = true))
                }
            } else {
                PtySession("/system/bin/sh", home.path, env)
            }
            created.also { sessions[root.path] = it }
        }
    }

    fun restart(context: Context, root: File, linuxEnv: LinuxEnv): Result<PtySession> {
        sessions.remove(root.path)?.let { runCatching { it.finish() } }
        return session(context, root, linuxEnv)
    }

    fun endAll() {
        sessions.values.forEach { runCatching { it.finish() } }
        sessions.clear()
    }
}

private const val ESC = "\u001b"

@Composable
fun TerminalTab(vm: ChatViewModel) {
    val context = LocalContext.current
    val root = vm.workspace.root
    val linuxEnv = vm.linuxEnv
    val progress by linuxEnv.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var installJob by remember { mutableStateOf<Job?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val result = remember(root.path, generation) {
        if (generation == 0) TerminalHost.session(context, root, linuxEnv) else TerminalHost.restart(context, root, linuxEnv)
    }
    val session = result.getOrNull()
    if (session == null) {
        Text(
            "Could not start the terminal: ${result.exceptionOrNull()?.message ?: "unknown error"}",
            modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    var view by remember(session) { mutableStateOf<EmulatorView?>(null) }

    fun send(s: String) = session.write(s)
    fun arrow(c: Char) = send(if (view?.keypadApplicationMode == true) "${ESC}O$c" else "$ESC[$c")

    Column(Modifier.fillMaxSize()) {
        if (linuxEnv.isInstalled && linuxEnv.nativeBinariesPresent) {
            Text(
                "✓ Linux Installed (Alpine)",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text(if (linuxEnv.isInstalled) "PRoot binaries required" else if (linuxEnv.isPartial) "Linux install incomplete" else "Install full Linux", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (linuxEnv.isInstalled) "Alpine is installed, but libproot.so or libproot-loader.so is missing. Add both ABI-matched files under app/src/main/jniLibs/ and rebuild. The terminal is using Android sh for now."
                        else if (linuxEnv.isPartial) "The Alpine files are incomplete or damaged. Tap Repair to install them again."
                        else "About 4 MB download. Then install tools with: apk add git python3 nano nodejs gcc",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (linuxEnv.isInstalled) {
                        Text("The root filesystem is ready; installing it again will not add the missing native binaries.", style = MaterialTheme.typography.bodySmall)
                    } else if (installJob?.isActive == true) {
                        LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(progress.message, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { installJob?.cancel(); installJob = null; linuxEnv.cancelProgress() }) { Text("Cancel") }
                    } else if (!linuxEnv.isInstalled) {
                        installError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = {
                            installError = null
                            installJob = scope.launch {
                                try { linuxEnv.install(); generation++ }
                                catch (e: kotlinx.coroutines.CancellationException) { linuxEnv.cancelProgress(); throw e }
                                catch (e: Throwable) { installError = e.message ?: "Linux installation failed. Please retry." }
                            }
                        }) { Text(if (installError != null) "Retry" else if (linuxEnv.isPartial) "Repair" else "Install") }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val metrics = DisplayMetrics().also { ctx.resources.displayMetrics.let { m -> it.setTo(m) } }
                    session.setColorScheme(ColorScheme(0xFFE6E6E6.toInt(), 0xFF000000.toInt()))
                    EmulatorView(ctx, session, metrics).apply {
                        setTextSize(13)
                        setUseCookedIME(false)
                        setExtGestureListener(object : GestureDetector.SimpleOnGestureListener() {
                            override fun onSingleTapUp(e: MotionEvent): Boolean {
                                requestFocus()
                                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                    .showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)
                                return true
                            }
                        })
                        isFocusableInTouchMode = true
                        requestFocus()
                        onResume()
                        view = this
                    }
                },
                onRelease = { it.onPause() },
            )
        }
        // Keys a phone keyboard does not have.
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyChip("ESC") { send(ESC) }
            KeyChip("TAB") { send("\t") }
            KeyChip("CTRL") { view?.sendControlKey() }
            KeyChip("^C") { session.interrupt() }
            KeyChip("^D") { send("\u0004") }
            KeyChip("↑") { arrow('A') }
            KeyChip("↓") { arrow('B') }
            KeyChip("←") { arrow('D') }
            KeyChip("→") { arrow('C') }
            KeyChip("/") { send("/") }
            KeyChip("-") { send("-") }
            KeyChip("|") { send("|") }
            KeyChip("~") { send("~") }
            KeyChip("Restart") { generation++ }
        }
    }

    DisposableEffect(session) { onDispose { view = null } }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp) })
}

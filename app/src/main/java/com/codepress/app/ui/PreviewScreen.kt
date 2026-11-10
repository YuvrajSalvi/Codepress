package com.codepress.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import com.codepress.app.data.LanPreview
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** [path] is the workspace-relative path when the preview shows a real project file (null for code previewed from chat). */
data class PreviewTarget(val file: File, val title: String, val path: String? = null)

/** Runs an HTML / SVG file inside the app. The ⋮ menu copies, inspects or downloads the exact file being shown. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(vm: ChatViewModel, target: PreviewTarget, onClose: () -> Unit) {
    var inspecting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var pcMode by rememberSaveable { mutableStateOf(false) }
    var lanUrl by remember { mutableStateOf<String?>(null) }
    var lanError by remember { mutableStateOf<String?>(null) }
    var lanDialog by remember { mutableStateOf(false) }
    val download = rememberFileDownloader(vm)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Serve the project folder (or the folder of a chat preview) and show the address to open from another device.
    fun startLan() {
        scope.launch {
            val folder = if (target.path != null) vm.workspace.root else (target.file.parentFile ?: target.file)
            val rel = if (target.path != null) target.path else target.file.name
            val base = withContext(Dispatchers.IO) { LanPreview.start(folder) }
            if (base == null) {
                lanUrl = null
                lanError = "No Wi-Fi / hotspot address found. Connect the phone to a Wi-Fi network (or turn on its hotspot) and try again."
            } else {
                lanError = null
                lanUrl = base + "/" + rel.trimStart('/').split('/').joinToString("/") { Uri.encode(it) }
            }
            lanDialog = true
        }
    }

    if (inspecting) {
        BackHandler { inspecting = false }
        InspectScreen(vm, target.file, target.title) { inspecting = false }
        return
    }
    BackHandler { onClose() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(target.title, maxLines = 1) },
                navigationIcon = { IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Close preview", onClose) },
                actions = {
                    IconAction(Icons.Rounded.Refresh, "Reload", { web?.reload() })
                    IconAction(Icons.Rounded.MoreVert, "More", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Copy code") }, onClick = {
                            menu = false
                            vm.copyFileText(target.file, "Code copied")
                        })
                        DropdownMenuItem(text = { Text("Inspect code") }, onClick = { menu = false; inspecting = true })
                        target.path?.let { p ->
                            DropdownMenuItem(text = { Text("Download file") }, onClick = { menu = false; download(p) })
                            DropdownMenuItem(text = { Text("Open in Files") }, onClick = { menu = false; vm.openInFiles(p) })
                        }
                        DropdownMenuItem(
                            text = { Text(if (pcMode) "Phone mode" else "PC mode") },
                            onClick = {
                                menu = false
                                pcMode = !pcMode
                                web?.let { applyViewMode(it, pcMode); it.reload() }
                            },
                        )
                        DropdownMenuItem(text = { Text("View on browser (LAN)") }, onClick = { menu = false; startLan() })
                        DropdownMenuItem(text = { Text("Refresh preview") }, onClick = { menu = false; web?.reload() })
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = false
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (pcMode) view.evaluateJavascript(FORCE_DESKTOP_VIEWPORT_JS, null)
                            }
                        }
                        webChromeClient = WebChromeClient()
                        applyViewMode(this, pcMode)
                        loadUrl(Uri.fromFile(target.file).toString())
                        web = this
                    }
                },
                onRelease = {
                    it.stopLoading()
                    it.destroy()
                },
            )
        }
    }

    if (lanDialog) {
        AlertDialog(
            onDismissRequest = { lanDialog = false },
            title = { Text(if (lanUrl != null) "Open in a browser" else "Not on a network") },
            text = {
                val url = lanUrl
                if (url != null) {
                    Column {
                        Text("On a laptop or another phone connected to the same Wi-Fi, open this address:")
                        SelectionContainer { Text(url, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.primary) }
                        Text(
                            "The server only runs while CodePress is open, and anyone on your Wi-Fi can see the project files. Stop it when you are done.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(lanError ?: "")
                }
            },
            confirmButton = {
                val url = lanUrl
                if (url != null) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(url))
                        lanDialog = false
                    }) { Text("Copy link") }
                } else {
                    TextButton(onClick = { lanDialog = false }) { Text("OK") }
                }
            },
            dismissButton = {
                val url = lanUrl
                if (url != null) {
                    Column {
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            lanDialog = false
                        }) { Text("Open here") }
                        TextButton(onClick = { LanPreview.stop(); lanDialog = false }) { Text("Stop server") }
                    }
                }
            },
        )
    }
}


private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private const val FORCE_DESKTOP_VIEWPORT_JS = """
(function(){
  var m=document.querySelector('meta[name=viewport]');
  if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}
  m.setAttribute('content','width=1280');
})();
"""

/** PC mode: desktop user agent and a 1280px wide layout viewport, zoomed out to fit the screen. */
@SuppressLint("SetJavaScriptEnabled")
private fun applyViewMode(web: WebView, pc: Boolean) {
    web.settings.apply {
        userAgentString = if (pc) DESKTOP_UA else WebSettings.getDefaultUserAgent(web.context)
        useWideViewPort = pc
        loadWithOverviewMode = pc
    }
}

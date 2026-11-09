package com.codepress.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebChromeClient
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
    val download = rememberFileDownloader(vm)

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
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
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
}

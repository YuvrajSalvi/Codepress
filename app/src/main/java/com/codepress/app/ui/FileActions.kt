package com.codepress.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** "Save as" picker that keeps the exact file name (no .bin / .txt added by the system). */
class CreateDocWithType : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

fun mimeFor(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

private val PREVIEW_EXT = setOf("html", "htm", "svg", "php")

/** html / svg open in the in-app preview; php shows a short "coming later" message. */
fun isPreviewable(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in PREVIEW_EXT

/** Returns a function that asks where to save a workspace file, then copies it there. */
@Composable
fun rememberFileDownloader(vm: ChatViewModel): (String) -> Unit {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(CreateDocWithType()) { uri ->
        val path = pending
        pending = null
        if (uri != null && path != null) vm.exportFile(path, uri)
    }
    return { path ->
        pending = path
        launcher.launch(path.substringAfterLast('/') to mimeFor(path))
    }
}

package com.codepress.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.codepress.app.data.Connectors
import com.codepress.app.data.SecureSettings
import com.codepress.app.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

@Composable
private fun ConnectorCard(title: String, description: String, status: String, connected: Boolean, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "● $status",
                style = MaterialTheme.typography.bodyMedium,
                color = if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun Msg(text: String?, error: Boolean) {
    if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun FirebaseCard(s: SecureSettings) {
    var config by remember { mutableStateOf(s.firebaseConfig) }
    var saved by remember { mutableStateOf(s.firebaseConfig) }
    var msg by remember { mutableStateOf<String?>(null) }
    val id = Connectors.firebaseProjectId(saved)
    ConnectorCard(
        title = "Firebase",
        description = "Paste your Firebase SDK config (the firebaseConfig snippet from the Firebase console, or the whole google-services.json). The AI uses it when it builds web or Android apps that need Firebase.",
        status = when {
            saved.isBlank() -> "Not connected"
            id != null -> "Config saved · project $id"
            else -> "Config saved"
        },
        connected = saved.isNotBlank(),
    ) {
        OutlinedTextField(
            value = config,
            onValueChange = { config = it },
            label = { Text("Firebase SDK config") },
            placeholder = { Text("const firebaseConfig = { apiKey: \"…\", projectId: \"…\" }") },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp),
        )
        Msg(msg, false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                s.firebaseConfig = config
                saved = s.firebaseConfig
                msg = if (saved.isBlank()) "Nothing to save." else if (Connectors.firebaseProjectId(saved) == null) "Saved, but no project id was found in the text. Check that you pasted the whole config." else "Saved."
            }) { Text("Save") }
            if (saved.isNotBlank()) TextButton(onClick = {
                s.firebaseConfig = ""
                config = ""
                saved = ""
                msg = "Firebase config removed from this phone."
            }) { Text("Remove") }
        }
    }
}

private suspend fun testSupabase(url: String, anonKey: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("$url/auth/v1/settings")
        .header("apikey", anonKey)
        .build()
    Http.client.newCall(request).execute().use { r ->
        when {
            r.code == 401 || r.code == 403 -> throw IOException("Supabase does not accept this anon key.")
            r.code == 404 -> throw IOException("No Supabase project found at this URL.")
            !r.isSuccessful -> throw IOException("Supabase answered with error ${r.code}.")
            else -> "Connected to Supabase."
        }
    }
}

@Composable
fun SupabaseCard(s: SecureSettings) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(s.supabaseUrl) }
    var key by remember { mutableStateOf(s.supabaseAnonKey) }
    var savedUrl by remember { mutableStateOf(s.supabaseUrl) }
    var savedKey by remember { mutableStateOf(s.supabaseAnonKey) }
    var msg by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val ready = savedUrl.isNotBlank() && savedKey.isNotBlank()
    ConnectorCard(
        title = "Supabase",
        description = "Enter your project URL and the anon (public) key from Supabase → Project settings → API. The AI uses them for databases, login and storage in the apps it builds. Never paste the service_role key.",
        status = if (ready) "Saved · $savedUrl" else "Not connected",
        connected = ready,
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Project URL") },
            placeholder = { Text("https://xxxx.supabase.co") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        SecretField("Anon key", key) { key = it }
        Msg(msg, error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !busy, onClick = {
                val u = Connectors.normalizeSupabaseUrl(url)
                if (u.isEmpty() || key.isBlank()) {
                    error = true
                    msg = "Enter both the project URL and the anon key."
                    return@Button
                }
                s.supabaseUrl = u
                s.supabaseAnonKey = key
                savedUrl = s.supabaseUrl
                savedKey = s.supabaseAnonKey
                url = u
                busy = true
                error = false
                msg = "Checking with Supabase…"
                scope.launch {
                    try {
                        msg = testSupabase(u, key.trim())
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        error = true
                        msg = (e.message ?: "Could not reach Supabase. Check your internet.") + " The values were saved anyway."
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Checking…" else "Save and test") }
            if (ready) TextButton(onClick = {
                s.supabaseUrl = ""
                s.supabaseAnonKey = ""
                url = ""
                key = ""
                savedUrl = ""
                savedKey = ""
                error = false
                msg = "Supabase removed from this phone."
            }) { Text("Remove") }
        }
    }
}

@Composable
fun ImgbbCard(s: SecureSettings) {
    var key by remember { mutableStateOf(s.imgbbKey) }
    var saved by remember { mutableStateOf(s.imgbbKey) }
    var msg by remember { mutableStateOf<String?>(null) }
    ConnectorCard(
        title = "ImgBB",
        description = "Add your ImgBB API key (api.imgbb.com) so the AI can upload images and use the links in your apps.",
        status = if (saved.isNotBlank()) "API key saved" else "Not connected",
        connected = saved.isNotBlank(),
    ) {
        SecretField("ImgBB API key", key) { key = it }
        Msg(msg, false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                s.imgbbKey = key
                saved = s.imgbbKey
                msg = if (saved.isBlank()) "Nothing to save." else "Saved."
            }) { Text("Save") }
            if (saved.isNotBlank()) TextButton(onClick = {
                s.imgbbKey = ""
                key = ""
                saved = ""
                msg = "ImgBB key removed from this phone."
            }) { Text("Remove") }
        }
    }
}

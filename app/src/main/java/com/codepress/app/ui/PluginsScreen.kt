package com.codepress.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.codepress.app.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

private const val TOKEN_URL = "https://github.com/settings/tokens/new?scopes=repo,workflow&description=CodePress"

private val TOKEN_STEPS = listOf(
    "Log in to GitHub (github.com). If you do not have an account, create one first, it is free.",
    "Tap “Open GitHub token page” below. It opens the “New personal access token” page with the right permission already ticked.",
    "If you open GitHub yourself instead: profile photo → Settings → Developer settings (last item in the menu) → Personal access tokens → Tokens (classic) → Generate new token (classic).",
    "Note: type CodePress. Expiration: pick 90 days (or No expiration).",
    "Make sure the boxes “repo” and “workflow” are both ticked (workflow is needed to build APKs). Nothing else is needed.",
    "Scroll down and tap “Generate token”.",
    "Copy the token. It starts with ghp_ and GitHub shows it only once, so copy it right away.",
    "Come back here, type your GitHub username (the name in github.com/yourname), paste the token and tap “Save and test”.",
)

private suspend fun githubLogin(token: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("https://api.github.com/user")
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "CodePress")
        .build()
    Http.client.newCall(request).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (r.code == 401) throw IOException("GitHub does not accept this token. It may be wrong or expired.")
        if (!r.isSuccessful) throw IOException("GitHub answered with error ${r.code}.")
        JSONObject(body).optString("login")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val s = vm.settings
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var user by remember { mutableStateOf(s.githubUser) }
    var token by remember { mutableStateOf(s.githubToken) }
    var connectedAs by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(s.githubToken.isBlank()) }

    fun saveAndTest() {
        if (user.isBlank() || token.isBlank()) {
            error = true
            message = "Enter both your GitHub username and the token."
            return
        }
        s.githubUser = user
        s.githubToken = token
        busy = true
        error = false
        message = "Checking with GitHub…"
        scope.launch {
            try {
                val login = githubLogin(token.trim())
                connectedAs = login
                error = !login.equals(user.trim(), ignoreCase = true)
                message = if (error) "Token works, but it belongs to “$login”, not “${user.trim()}”. Check the username." else "Connected as $login."
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                connectedAs = null
                error = true
                message = e.message ?: "Could not reach GitHub. Check your internet."
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Plugins") },
                navigationIcon = { IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack) },
            )
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("GitHub", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Connect your GitHub account so your code can be pushed to a repository. No commands needed: you only give your username and a token.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when {
                            connectedAs != null && !error -> "● Connected as $connectedAs"
                            s.githubToken.isNotBlank() -> "● Token saved"
                            else -> "● Not connected"
                        },
                        color = if (connectedAs != null && !error) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    TextButton(onClick = { guide = !guide }) {
                        Text("How do I get a token?")
                        androidx.compose.material3.Icon(if (guide) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                    }
                    if (guide) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TOKEN_STEPS.forEachIndexed { i, step ->
                                Row {
                                    Text("${i + 1}.", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                                    Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                }
                            }
                            OutlinedButton(onClick = { uri.openUri(TOKEN_URL) }) { Text("Open GitHub token page") }
                        }
                    }

                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("GitHub username") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecretField("Personal access token (ghp_…)", token) { token = it }
                    message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { saveAndTest() }, enabled = !busy) { Text(if (busy) "Checking…" else "Save and test") }
                        if (s.githubToken.isNotBlank()) {
                            TextButton(onClick = {
                                s.githubToken = ""
                                token = ""
                                connectedAs = null
                                error = false
                                message = "Disconnected. The token was removed from this phone."
                            }) { Text("Disconnect") }
                        }
                    }
                    Text(
                        "The token is stored encrypted on this phone. Pushing code from the chat comes in Phase 2.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("More plugins are coming soon.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

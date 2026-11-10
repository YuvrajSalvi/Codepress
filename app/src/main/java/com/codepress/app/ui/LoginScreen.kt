package com.codepress.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codepress.app.data.CloudAuth
import kotlinx.coroutines.launch

/** First-run screen. "Skip" keeps the app fully usable offline; signing in turns on chat backup. */
@Composable
fun LoginScreen(vm: ChatViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch {
            try {
                CloudAuth.finishSignIn(result.data)
                vm.restoreCloudChats()
                onDone()
            } catch (e: Throwable) {
                error = when {
                    e is com.google.android.gms.common.api.ApiException && e.statusCode == 10 ->
                        "Google sign-in is not set up for this APK yet (error 10). Add this build's SHA-1 in Firebase, then download google-services.json again."
                    e is com.google.android.gms.common.api.ApiException && e.statusCode == 12501 -> "Sign-in cancelled."
                    else -> e.message ?: "Sign-in failed."
                }
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("CodePress", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in with Google to back up your chats and restore them on any device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    error = null; busy = true
                    runCatching { launcher.launch(CloudAuth.client(context).signInIntent) }
                        .onFailure { error = it.message; busy = false }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue with Google") }
            TextButton(onClick = onDone) { Text("Skip for now") }
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

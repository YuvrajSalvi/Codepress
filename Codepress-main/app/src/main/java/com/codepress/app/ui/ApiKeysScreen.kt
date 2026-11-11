package com.codepress.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codepress.app.data.ProviderType

/**
 * API keys live on their own page. With a phone lock set (PIN, pattern, password or fingerprint)
 * the phone asks to unlock before the keys can be seen or changed. Without a lock the keys are
 * shown, with a clear warning to set one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val keyguard = remember { ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    val secure = remember { keyguard.isDeviceSecure }
    var unlocked by remember { mutableStateOf(!secure) }
    var failed by remember { mutableStateOf(false) }
    var autoTried by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            unlocked = true
            failed = false
        } else {
            failed = true
        }
    }

    fun unlock() {
        val intent = keyguard.createConfirmDeviceCredentialIntent("Unlock API keys", "Confirm it is you to view or change your API keys")
        if (intent != null) launcher.launch(intent) else unlocked = true
    }

    LaunchedEffect(Unit) {
        if (secure && !autoTried) {
            autoTried = true
            unlock()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("API keys") },
                navigationIcon = { IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack) },
            )
        },
    ) { inner ->
        if (!unlocked) {
            Column(
                Modifier.padding(inner).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text("Your keys are locked", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Use your fingerprint, PIN, pattern or password to open them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (failed) {
                    Spacer(Modifier.height(8.dp))
                    Text("Not unlocked. Try again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = { unlock() }) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Unlock")
                }
            }
        } else {
            val s = vm.settings
            var anthropic by remember { mutableStateOf(s.apiKey(ProviderType.ANTHROPIC)) }
            var gemini by remember { mutableStateOf(s.apiKey(ProviderType.GEMINI)) }
            var openAi by remember { mutableStateOf(s.apiKey(ProviderType.OPENAI)) }
            Column(
                Modifier.padding(inner).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!secure) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                Text("  Your phone has no screen lock", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                "Anyone who picks up your phone can read these keys. Please set a PIN, pattern, password or fingerprint in your phone's Settings.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                                Text("Open phone security settings")
                            }
                        }
                    }
                }
                Text(
                    "Add a key for each service you use. Every model that has a key and a model name shows up in the switcher next to the chat box.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Anthropic (Claude)", style = MaterialTheme.typography.titleMedium)
                SecretField("API key", anthropic) { anthropic = it; s.setApiKey(ProviderType.ANTHROPIC, it); vm.bump() }
                Text("Google Gemini", style = MaterialTheme.typography.titleMedium)
                SecretField("API key", gemini) { gemini = it; s.setApiKey(ProviderType.GEMINI, it); vm.bump() }
                Text("OpenAI-compatible", style = MaterialTheme.typography.titleMedium)
                SecretField("API key (empty for local servers)", openAi) { openAi = it; s.setApiKey(ProviderType.OPENAI, it); vm.bump() }
                Text(
                    "Keys are stored encrypted on this phone and are only sent to the service they belong to. The on-device model needs no key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

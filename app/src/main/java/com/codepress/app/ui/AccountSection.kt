package com.codepress.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.R
import com.codepress.app.data.AuthManager
import kotlinx.coroutines.launch

/** Google account card shown at the top of Settings. */
@Composable
fun AccountSection(auth: AuthManager) {
    val state by auth.state.collectAsStateWithLifecycle()
    val error by auth.error.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val webClientId = remember { ctx.getString(R.string.default_web_client_id) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Account", style = MaterialTheme.typography.titleMedium)
        when (val s = state) {
            is AuthManager.State.SignedIn -> {
                Text(s.name ?: "Signed in")
                s.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedButton(onClick = { scope.launch { auth.signOut(ctx) } }) { Text("Sign out") }
            }
            AuthManager.State.Busy -> CircularProgressIndicator()
            AuthManager.State.SignedOut -> {
                Text(
                    "Sign in with Google to link CodePress to your account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { scope.launch { auth.signIn(ctx, webClientId) } }) { Text("Sign in with Google") }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

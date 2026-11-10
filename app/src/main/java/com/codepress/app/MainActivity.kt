package com.codepress.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.codepress.app.data.CloudAuth
import com.codepress.app.ui.LoginScreen
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codepress.app.ui.ChatViewModel
import com.codepress.app.ui.CodePressRoot
import com.codepress.app.ui.CodePressTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CodePressTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: ChatViewModel = viewModel()
                    val prefs = getSharedPreferences("codepress_boot", MODE_PRIVATE)
                    var loginHandled by rememberSaveable {
                        mutableStateOf(CloudAuth.user != null || prefs.getBoolean("login_skipped", false))
                    }
                    if (!loginHandled) {
                        LoginScreen(vm) { prefs.edit().putBoolean("login_skipped", true).apply(); loginHandled = true }
                    } else {
                        CodePressRoot(vm)
                    }
                }
            }
        }
    }
}

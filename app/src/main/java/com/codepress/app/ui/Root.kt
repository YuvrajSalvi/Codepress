package com.codepress.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

private enum class Screen { CHAT, SETTINGS, FILES }

@Composable
fun CodePressRoot(vm: ChatViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT.name) }
    val current = Screen.valueOf(screen)
    BackHandler(enabled = current != Screen.CHAT) { screen = Screen.CHAT.name }
    when (current) {
        Screen.SETTINGS -> SettingsScreen(vm) { screen = Screen.CHAT.name }
        Screen.FILES -> FilesScreen(vm) { screen = Screen.CHAT.name }
        Screen.CHAT -> ChatScreen(vm, onSettings = { screen = Screen.SETTINGS.name }, onFiles = { screen = Screen.FILES.name })
    }
}

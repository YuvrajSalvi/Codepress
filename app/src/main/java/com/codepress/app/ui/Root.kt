package com.codepress.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.vector.ImageVector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

private enum class Screen(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Rounded.Chat),
    FILES("Files", Icons.Rounded.Folder),
    SHELL("Shell", Icons.Rounded.Terminal),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@Composable
fun CodePressRoot(vm: ChatViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT.name) }
    val current = Screen.valueOf(screen)
    BackHandler(enabled = current != Screen.CHAT) { screen = Screen.CHAT.name }

    if (current == Screen.SETTINGS) {
        SettingsScreen(vm) { screen = Screen.CHAT.name }
    } else {
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f)) {
                when (current) {
                    Screen.FILES -> FilesScreen(vm) { screen = Screen.CHAT.name }
                    Screen.SHELL -> ShellScreen(vm)
                    else -> ChatScreen(vm, onSettings = { screen = Screen.SETTINGS.name }, onOpenShell = { screen = Screen.SHELL.name })
                }
            }
            if (!imeVisible) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(Screen.CHAT, Screen.FILES, Screen.SHELL).forEach { s ->
                        NavigationBarItem(
                            selected = current == s,
                            onClick = { screen = s.name },
                            icon = { Icon(s.icon, contentDescription = s.label) },
                            label = { Text(s.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        }
    }
}

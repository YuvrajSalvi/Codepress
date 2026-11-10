package com.codepress.app.ui

import androidx.compose.runtime.remember

import androidx.compose.runtime.mutableIntStateOf

import androidx.compose.material.icons.rounded.Code

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.agent.ShellOrigin
import com.codepress.app.data.Project

private enum class Screen(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Rounded.Chat),
    FILES("Files", Icons.Rounded.Folder),
    EDITOR("Editor", Icons.Rounded.Code),
    SHELL("Shell", Icons.Rounded.Terminal),
}

private const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"

@Composable
fun CodePressRoot(vm: ChatViewModel) {
    val project by vm.project.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val inspect by vm.inspect.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // Android 13+ needs a one-time permission to show notifications.
    val ctx = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, NOTIFICATION_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(NOTIFICATION_PERMISSION)
        }
    }

    // First launch: ask for All files access so projects can live in the public /CodePress folder.
    var askStorage by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = ctx.getSharedPreferences("codepress_boot", android.content.Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 30 && vm.needsStorageAccess && !prefs.getBoolean("storage_asked", false)) {
            prefs.edit().putBoolean("storage_asked", true).apply()
            askStorage = true
        }
    }
    LifecycleResumeEffect(Unit) { vm.adoptSharedStorage(); onPauseOrDispose { } }
    if (askStorage) {
        AlertDialog(
            onDismissRequest = { askStorage = false },
            title = { Text("Create the CodePress folder?") },
            text = { Text("Allow \"All files access\" so CodePress can keep every project as a folder in /CodePress on your phone. You can skip this; projects then stay in private app storage.") },
            confirmButton = {
                TextButton(onClick = {
                    askStorage = false
                    val uri = android.net.Uri.parse("package:${ctx.packageName}")
                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)) }
                        .onFailure { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = { askStorage = false }) { Text("Not now") } },
        )
    }

    val p = project
    val pv = preview
    val ins = inspect
    when {
        ins != null -> {
            BackHandler { vm.closeInspect() }
            InspectScreen(vm, ins.file, ins.title) { vm.closeInspect() }
        }
        pv != null -> PreviewScreen(vm, pv) { vm.closePreview() }
        settingsOpen -> {
            BackHandler { settingsOpen = false }
            SettingsScreen(vm) { settingsOpen = false }
        }
        p == null -> ProjectsScreen(vm) { settingsOpen = true }
        else -> key(p.id) { ProjectHome(vm, p) { settingsOpen = true } }
    }
}

@Composable
private fun ProjectHome(vm: ChatViewModel, project: Project, onSettings: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT.name) }
    val current = Screen.valueOf(screen)
    BackHandler(enabled = current != Screen.CHAT) { screen = Screen.CHAT.name }
    BackHandler(enabled = current == Screen.CHAT) { vm.closeProject() }

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // Jump to a tab when something else asks for it (Open in editor, Open in Files, Ask AI...).
    val editorReq by vm.editorRequest.collectAsStateWithLifecycle()
    val filesReq by vm.filesRequest.collectAsStateWithLifecycle()
    val chatReq by vm.chatRequest.collectAsStateWithLifecycle()
    var seenEditor by remember { mutableIntStateOf(editorReq) }
    var seenFiles by remember { mutableIntStateOf(filesReq) }
    var seenChat by remember { mutableIntStateOf(chatReq) }
    LaunchedEffect(editorReq) { if (editorReq != seenEditor) { seenEditor = editorReq; screen = Screen.EDITOR.name } }
    LaunchedEffect(filesReq) { if (filesReq != seenFiles) { seenFiles = filesReq; screen = Screen.FILES.name } }
    LaunchedEffect(chatReq) { if (chatReq != seenChat) { seenChat = chatReq; screen = Screen.CHAT.name } }
    val unseenFiles by vm.unseenFiles.collectAsStateWithLifecycle()
    val shellEntries by vm.shell.entries.collectAsStateWithLifecycle()
    val shellSeen by vm.shellSeen.collectAsStateWithLifecycle()
    val unseenCommands = shellEntries.count { it.origin == ShellOrigin.AI && it.id > shellSeen }

    // Opening a tab counts as "checked": the red number goes away.
    LaunchedEffect(current, unseenFiles) {
        if (current == Screen.FILES && unseenFiles > 0) vm.markFilesSeen()
    }
    LaunchedEffect(current, shellEntries.size) {
        if (current == Screen.SHELL) shellEntries.maxOfOrNull { it.id }?.let { vm.markShellSeen(it) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f)) {
            when (current) {
                Screen.FILES -> FilesScreen(vm, onBack = { screen = Screen.CHAT.name }, onOpenEditor = { screen = Screen.EDITOR.name })
                Screen.EDITOR -> EditorScreen(vm) { screen = Screen.CHAT.name }
                Screen.SHELL -> ShellScreen(vm)
                else -> ChatScreen(
                    vm,
                    project = project,
                    onSettings = onSettings,
                    onOpenShell = { screen = Screen.SHELL.name },
                    onOpenFiles = { screen = Screen.FILES.name },
                )
            }
        }
        if (!imeVisible) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Screen.entries.forEach { s ->
                    val badge = when (s) {
                        Screen.FILES -> unseenFiles
                        Screen.SHELL -> unseenCommands
                        else -> 0
                    }
                    NavigationBarItem(
                        selected = current == s,
                        onClick = { screen = s.name },
                        icon = {
                            if (badge > 0 && current != s) {
                                BadgedBox(badge = { Badge { Text(if (badge > 99) "99+" else "$badge") } }) {
                                    Icon(s.icon, contentDescription = s.label)
                                }
                            } else {
                                Icon(s.icon, contentDescription = s.label)
                            }
                        },
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

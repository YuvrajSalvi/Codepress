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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.data.BUILT_IN_SKILLS
import com.codepress.app.data.SkillInstaller
import com.codepress.app.data.SkillRepo
import com.codepress.app.data.parseSkillRepo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val s = vm.settings
    val scope = rememberCoroutineScope()
    val project by vm.project.collectAsStateWithLifecycle()
    val installer = remember { SkillInstaller { s.githubToken } }
    var refresh by remember { mutableIntStateOf(0) }
    var busyFolder by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(s.customSkills.mapNotNull { parseSkillRepo(it) }) }

    val root = project?.let { vm.workspace.root }

    fun install(skill: SkillRepo) {
        val r = root ?: return
        busyFolder = skill.folder
        error = false
        status = "Installing ${skill.title}…"
        scope.launch {
            try {
                val n = installer.install(r, skill) { status = it }
                status = "${skill.title} installed: $n files in skills/${skill.folder}."
                vm.bump()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = true
                status = e.message ?: "Installing failed. Check your internet."
            } finally {
                busyFolder = null
                refresh++
            }
        }
    }

    fun addCustom() {
        val repo = parseSkillRepo(link)
        if (repo == null) {
            error = true
            status = "That is not a GitHub repository link. Use a link like https://github.com/name/repo"
            return
        }
        if (BUILT_IN_SKILLS.any { it.folder.equals(repo.folder, true) } || custom.any { it.folder.equals(repo.folder, true) }) {
            error = true
            status = "${repo.title} is already in the library."
            return
        }
        custom = custom + repo
        s.customSkills = custom.map { it.url + (it.ref?.let { r -> "/tree/$r" } ?: "") }
        link = ""
        install(repo)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Skills") },
                navigationIcon = { IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack) },
            )
        },
    ) { inner ->
        Column(
            Modifier.padding(inner).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Skills are folders of instructions and files the AI follows. Installing a skill downloads it and unzips it into the skills folder of your open project. The AI then reads it when a task matches.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (project == null) {
                Text(
                    "Open a project first. Skills are installed into the project you have open.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("Installing into “${project?.name}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("Library", style = MaterialTheme.typography.titleMedium)
            BUILT_IN_SKILLS.forEach { skill ->
                SkillRow(skill, root, installer, refresh, busyFolder, onInstall = { install(skill) }, onRemove = {
                    root?.let { installer.remove(it, skill) }
                    vm.bump()
                    refresh++
                    status = "${skill.title} removed from this project."
                    error = false
                })
            }

            if (custom.isNotEmpty()) {
                Text("Your skills", style = MaterialTheme.typography.titleMedium)
                custom.forEach { skill ->
                    SkillRow(skill, root, installer, refresh, busyFolder, onInstall = { install(skill) }, onRemove = {
                        root?.let { installer.remove(it, skill) }
                        vm.bump()
                        refresh++
                        status = "${skill.title} removed from this project."
                        error = false
                    }, onForget = {
                        custom = custom - skill
                        s.customSkills = custom.map { it.url + (it.ref?.let { r -> "/tree/$r" } ?: "") }
                    })
                }
            }

            Text("Add custom skill", style = MaterialTheme.typography.titleMedium)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Paste the link of a GitHub repository (https://github.com/name/repo). Public repositories work without a login; for a private one, save your GitHub token in Connectors first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        label = { Text("GitHub repository link") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { addCustom() }, enabled = root != null && busyFolder == null && link.isNotBlank()) { Text("Install") }
                }
            }
        }
    }
}

@Composable
private fun SkillRow(
    skill: SkillRepo,
    root: java.io.File?,
    installer: SkillInstaller,
    refresh: Int,
    busyFolder: String?,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onForget: (() -> Unit)? = null,
) {
    val installed = refresh >= 0 && root != null && installer.isInstalled(root, skill)
    val busy = busyFolder == skill.folder
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(skill.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (installed) "Installed in skills/${skill.folder}" else "Not installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (installed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                busy -> CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp)
                installed -> Row {
                    OutlinedButton(onClick = onInstall, enabled = busyFolder == null) { Text("Update") }
                    TextButton(onClick = onRemove, enabled = busyFolder == null) { Text("Remove") }
                }
                else -> Row {
                    Button(onClick = onInstall, enabled = root != null && busyFolder == null) { Text("Install") }
                    if (onForget != null) TextButton(onClick = onForget, enabled = busyFolder == null) { Text("Delete") }
                }
            }
        }
    }
}

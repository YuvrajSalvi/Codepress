package com.codepress.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.data.OPENAI_PRESETS
import com.codepress.app.data.ProviderType
import com.codepress.app.llm.ModelLists
import kotlinx.coroutines.launch

private const val SUGGESTED_MODEL_URL =
    "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"

@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("main") }
    BackHandler(enabled = page != "main") { page = "main" }
    when (page) {
        "keys" -> ApiKeysScreen(vm) { page = "main" }
        "plugins" -> PluginsScreen(vm) { page = "main" }
        else -> MainSettings(vm, onBack, openKeys = { page = "keys" }, openPlugins = { page = "plugins" })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MainSettings(vm: ChatViewModel, onBack: () -> Unit, openKeys: () -> Unit, openPlugins: () -> Unit) {
    val s = vm.settings
    val rev by vm.revision.collectAsStateWithLifecycle()
    val modelStatus by vm.modelStatus.collectAsStateWithLifecycle()
    val modelProgress by vm.modelProgress.collectAsStateWithLifecycle()
    val modelBusy by vm.modelBusy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(s.provider) }
    val anthropicKey = remember(rev) { s.apiKey(ProviderType.ANTHROPIC) }
    var anthropicModel by remember { mutableStateOf(s.model(ProviderType.ANTHROPIC)) }
    var openAiBase by remember { mutableStateOf(s.openAiBaseUrl) }
    val openAiKey = remember(rev) { s.apiKey(ProviderType.OPENAI) }
    var openAiModel by remember { mutableStateOf(s.model(ProviderType.OPENAI)) }
    val geminiKey = remember(rev) { s.apiKey(ProviderType.GEMINI) }
    var geminiModel by remember { mutableStateOf(s.model(ProviderType.GEMINI)) }
    var notificationsOn by remember { mutableStateOf(s.notificationsOn) }
    var notifyOnlyBackground by remember { mutableStateOf(s.notifyOnlyBackground) }
    var useLinuxAiCommands by remember { mutableStateOf(s.useLinuxForAiCommands) }
    var downloadUrl by remember { mutableStateOf("") }

    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    fun fetchModels() {
        scope.launch {
            fetching = true
            fetchError = null
            try {
                fetched = when (provider) {
                    ProviderType.ANTHROPIC -> ModelLists.anthropic(anthropicKey)
                    ProviderType.GEMINI -> ModelLists.gemini(geminiKey)
                    else -> ModelLists.openAi(openAiBase, openAiKey)
                }
                if (fetched.isEmpty()) fetchError = "The provider returned no models."
                if (provider == ProviderType.GEMINI && fetched.isNotEmpty() && geminiModel !in fetched) {
                    geminiModel = ModelLists.pickDefault(fetched)
                    s.setModel(ProviderType.GEMINI, geminiModel)
                    vm.bump()
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                fetched = emptyList()
                fetchError = friendlyError(e)
            } finally {
                fetching = false
            }
        }
    }

    // Gemini: as soon as a key is pasted, load the models it can use and pick a sensible one.
    LaunchedEffect(provider, geminiKey) {
        if (provider == ProviderType.GEMINI && geminiKey.length >= 30) {
            delay(800)
            fetchModels()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Settings") },
                navigationIcon = { IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack) },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AccountSection(vm.auth)
            HorizontalDivider()
            NavRow("API keys", "Locked with your fingerprint or phone password", openKeys)
            NavRow("Plugins", "GitHub", openPlugins)
            HorizontalDivider()
            Text("AI provider", style = MaterialTheme.typography.titleMedium)
            ProviderType.entries.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        provider = p
                        s.provider = p
                        fetched = emptyList()
                        fetchError = null
                        vm.bump()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = provider == p, onClick = null)
                    Text(p.label, Modifier.padding(start = 10.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = false, onClick = null, enabled = false)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Tilak — our own AI model", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Coming Soon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider()

            when (provider) {
                ProviderType.ANTHROPIC -> {
                    Text("Anthropic", style = MaterialTheme.typography.titleMedium)
                    KeyHint(anthropicKey.isNotBlank(), openKeys)
                    ModelField(anthropicModel, { anthropicModel = it; s.setModel(ProviderType.ANTHROPIC, it); vm.bump() }, fetched, fetching, fetchError, anthropicKey.isNotBlank(), ::fetchModels)
                }

                ProviderType.GEMINI -> {
                    Text("Google Gemini", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add your Gemini API key under API keys below. The models available to your key load by themselves, just tap one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    KeyHint(geminiKey.isNotBlank(), openKeys)
                    ModelField(geminiModel, { geminiModel = it; s.setModel(ProviderType.GEMINI, it); vm.bump() }, fetched, fetching, fetchError, geminiKey.isNotBlank(), ::fetchModels)
                }

                ProviderType.OPENAI -> {
                    Text("OpenAI-compatible API", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Works with OpenAI, OpenRouter, Groq, DeepSeek, Gemini, and local servers such as Ollama or LM Studio. Pick a preset, then add your key under API keys.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OPENAI_PRESETS.forEach { preset ->
                            AssistChip(onClick = { openAiBase = preset.baseUrl; s.openAiBaseUrl = preset.baseUrl; fetched = emptyList(); vm.bump() }, label = { Text(preset.label) })
                        }
                    }
                    OutlinedTextField(
                        value = openAiBase,
                        onValueChange = { openAiBase = it; s.openAiBaseUrl = it; vm.bump() },
                        label = { Text("Base URL") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    KeyHint(openAiKey.isNotBlank(), openKeys, optional = true)
                    ModelField(openAiModel, { openAiModel = it; s.setModel(ProviderType.OPENAI, it); vm.bump() }, fetched, fetching, fetchError, openAiBase.isNotBlank(), ::fetchModels)
                }

                ProviderType.LOCAL -> {
                    Text("On-device model", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Runs fully offline with llama.cpp. Use a small instruct model (1B to 4B parameters, Q4 quantisation) in .gguf format. " +
                            "Small models are much weaker at multi-step coding than cloud models, and the context window is limited.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val files = remember(rev) { vm.models.list() }
                    if (files.isEmpty()) {
                        Text("No models yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    files.forEach { f ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.localModelName == f.name, onClick = { vm.selectModel(f.name) })
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${f.length() / (1024 * 1024)} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconAction(Icons.Rounded.Delete, "Delete model", { vm.deleteModel(f.name) })
                        }
                    }
                    OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }, enabled = !modelBusy) { Text("Import .gguf from phone storage") }
                    OutlinedTextField(
                        value = downloadUrl,
                        onValueChange = { downloadUrl = it },
                        label = { Text("…or download from a direct .gguf link") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.downloadModel(downloadUrl) }, enabled = !modelBusy) { Text("Download") }
                        TextButton(onClick = { downloadUrl = SUGGESTED_MODEL_URL }) { Text("Suggested small model") }
                        if (modelBusy) TextButton(onClick = vm::cancelModelTask) { Text("Cancel") }
                    }
                    if (modelBusy) {
                        val p = modelProgress
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    modelStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        "The suggested link points to a Qwen2.5-Coder 1.5B model on Hugging Face (about 1 GB). If it fails, open huggingface.co, search for a GGUF model and copy its direct download link.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()
            Text("Shell environment", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use Linux for AI commands")
                    Text(
                        "Run the AI's run_command inside Alpine when Linux is installed. The workspace is mounted at /work.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = useLinuxAiCommands, onCheckedChange = {
                    useLinuxAiCommands = it
                    s.useLinuxForAiCommands = it
                })
            }
            HorizontalDivider()
            Text("Notifications", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show notifications")
                    Text(
                        "Model download progress, AI replies, new files and approval requests.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = notificationsOn, onCheckedChange = { notificationsOn = it; s.notificationsOn = it })
            }
            if (notificationsOn) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Only when the app is closed")
                        Text(
                            "Skip AI and file notifications while you are looking at the app. Download progress always shows.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = notifyOnlyBackground, onCheckedChange = { notifyOnlyBackground = it; s.notifyOnlyBackground = it })
                }
            }
            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                "CodePress · $TILAK_LINE",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KeyHint(hasKey: Boolean, openKeys: () -> Unit, optional: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (hasKey) "● API key saved" else if (optional) "● No API key (fine for local servers)" else "● No API key yet",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasKey) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = openKeys) { Text(if (hasKey) "Change" else "Add key") }
    }
}

@Composable
private fun ModelField(
    value: String,
    onChange: (String) -> Unit,
    fetched: List<String>,
    fetching: Boolean,
    error: String?,
    canFetch: Boolean,
    onFetch: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Model") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = onFetch, enabled = canFetch && !fetching) { Text(if (fetching) "Loading…" else "Fetch available models") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (fetched.isNotEmpty()) {
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            fetched.forEach { id ->
                TextButton(onClick = { onChange(id) }) { Text(id + if (id == value) "  ✓" else "") }
            }
        }
    }
}

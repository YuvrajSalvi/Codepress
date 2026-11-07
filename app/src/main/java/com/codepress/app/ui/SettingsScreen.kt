package com.codepress.app.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepress.app.data.OPENAI_PRESETS
import com.codepress.app.data.ProviderType
import com.codepress.app.llm.ModelLists
import kotlinx.coroutines.launch

private const val SUGGESTED_MODEL_URL =
    "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val s = vm.settings
    val rev by vm.revision.collectAsStateWithLifecycle()
    val modelStatus by vm.modelStatus.collectAsStateWithLifecycle()
    val modelProgress by vm.modelProgress.collectAsStateWithLifecycle()
    val modelBusy by vm.modelBusy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(s.provider) }
    var anthropicKey by remember { mutableStateOf(s.apiKey(ProviderType.ANTHROPIC)) }
    var anthropicModel by remember { mutableStateOf(s.model(ProviderType.ANTHROPIC)) }
    var openAiBase by remember { mutableStateOf(s.openAiBaseUrl) }
    var openAiKey by remember { mutableStateOf(s.apiKey(ProviderType.OPENAI)) }
    var openAiModel by remember { mutableStateOf(s.model(ProviderType.OPENAI)) }
    var autoApprove by remember { mutableStateOf(s.autoApprove) }
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
                fetched = if (provider == ProviderType.ANTHROPIC) ModelLists.anthropic(anthropicKey) else ModelLists.openAi(openAiBase, openAiKey)
                if (fetched.isEmpty()) fetchError = "The provider returned no models."
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
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
            HorizontalDivider()

            when (provider) {
                ProviderType.ANTHROPIC -> {
                    Text("Anthropic", style = MaterialTheme.typography.titleMedium)
                    SecretField("API key", anthropicKey) { anthropicKey = it; s.setApiKey(ProviderType.ANTHROPIC, it); vm.bump() }
                    ModelField(anthropicModel, { anthropicModel = it; s.setModel(ProviderType.ANTHROPIC, it); vm.bump() }, fetched, fetching, fetchError, anthropicKey.isNotBlank(), ::fetchModels)
                }

                ProviderType.OPENAI -> {
                    Text("OpenAI-compatible API", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Works with OpenAI, OpenRouter, Groq, DeepSeek, Gemini, and local servers such as Ollama or LM Studio. Pick a preset, then paste your key.",
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
                    SecretField("API key (leave empty for local servers)", openAiKey) { openAiKey = it; s.setApiKey(ProviderType.OPENAI, it); vm.bump() }
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
                            TextButton(onClick = { vm.deleteModel(f.name) }) { Text("Delete") }
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
            Text("Agent", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Run actions without asking")
                    Text(
                        "By default CodePress asks before it writes or edits a file or runs a command. Turn this on only if you trust the model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoApprove, onCheckedChange = { autoApprove = it; s.autoApprove = it })
            }
            HorizontalDivider()
            Text(
                "API keys are stored encrypted on this phone and are only sent to the provider you choose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth(),
    )
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

package com.codepress.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class ProviderType(val label: String) {
    ANTHROPIC("Anthropic (Claude)"),
    GEMINI("Google Gemini"),
    OPENAI("OpenAI-compatible"),
    LOCAL("On-device model (llama.cpp)"),
}

data class ConfiguredModel(val provider: ProviderType, val label: String, val model: String) {
    val title: String get() = "$label · $model"
}

data class OpenAiPreset(val label: String, val baseUrl: String)

val OPENAI_PRESETS = listOf(
    OpenAiPreset("OpenAI", "https://api.openai.com/v1"),
    OpenAiPreset("OpenRouter", "https://openrouter.ai/api/v1"),
    OpenAiPreset("Groq", "https://api.groq.com/openai/v1"),
    OpenAiPreset("DeepSeek", "https://api.deepseek.com/v1"),
    OpenAiPreset("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
    OpenAiPreset("Ollama (LAN)", "http://192.168.1.10:11434/v1"),
)

/** API keys and preferences, stored encrypted with a key held in the Android Keystore. */
class SecureSettings(context: Context) {

    private val prefs: SharedPreferences = create(context)

    var provider: ProviderType
        get() = runCatching { ProviderType.valueOf(prefs.getString("provider", null) ?: "") }.getOrDefault(ProviderType.ANTHROPIC)
        set(v) = prefs.edit().putString("provider", v.name).apply()

    fun apiKey(p: ProviderType): String = prefs.getString("key_${p.name}", "") ?: ""
    fun setApiKey(p: ProviderType, v: String) = prefs.edit().putString("key_${p.name}", v.trim()).apply()

    fun model(p: ProviderType): String = prefs.getString("model_${p.name}", defaultModel(p)) ?: defaultModel(p)
    fun setModel(p: ProviderType, v: String) = prefs.edit().putString("model_${p.name}", v.trim()).apply()

    /** Model names last loaded from the provider; the chat model switcher lists them. */
    fun cachedModels(p: ProviderType): List<String> =
        (prefs.getString("models_${p.name}", "") ?: "").split('\n').filter { it.isNotBlank() }
    fun setCachedModels(p: ProviderType, list: List<String>) =
        prefs.edit().putString("models_${p.name}", list.take(60).joinToString("\n")).apply()

    var openAiBaseUrl: String
        get() = prefs.getString("openai_base", OPENAI_PRESETS.first().baseUrl) ?: OPENAI_PRESETS.first().baseUrl
        set(v) = prefs.edit().putString("openai_base", v.trim()).apply()

    /** File name (inside the app's models folder) of the selected GGUF model. */
    var localModelName: String
        get() = prefs.getString("local_model", "") ?: ""
        set(v) = prefs.edit().putString("local_model", v).apply()

    var useLinuxForAiCommands: Boolean
        get() = prefs.getBoolean("use_linux_ai_commands_v2", true)
        set(v) = prefs.edit().putBoolean("use_linux_ai_commands_v2", v).apply()

    var autoApprove: Boolean
        get() = prefs.getBoolean("auto_approve", false)
        set(v) = prefs.edit().putBoolean("auto_approve", v).apply()

    var notificationsOn: Boolean
        get() = prefs.getBoolean("notify_on", true)
        set(v) = prefs.edit().putBoolean("notify_on", v).apply()

    /** When true, AI replies and file updates only notify while the app is not on screen. */
    var notifyOnlyBackground: Boolean
        get() = prefs.getBoolean("notify_bg_only", true)
        set(v) = prefs.edit().putBoolean("notify_bg_only", v).apply()

    var githubUser: String
        get() = prefs.getString("github_user", "") ?: ""
        set(v) = prefs.edit().putString("github_user", v.trim()).apply()

    var githubToken: String
        get() = prefs.getString("github_token", "") ?: ""
        set(v) = prefs.edit().putString("github_token", v.trim()).apply()

    // ---- connectors the AI can use when it builds apps (stored encrypted, like the API keys)
    /** The Firebase config the user pasted: a google-services.json or the JavaScript firebaseConfig snippet. */
    var firebaseConfig: String
        get() = prefs.getString("firebase_config", "") ?: ""
        set(v) = prefs.edit().putString("firebase_config", v.trim()).apply()

    var supabaseUrl: String
        get() = prefs.getString("supabase_url", "") ?: ""
        set(v) = prefs.edit().putString("supabase_url", v.trim()).apply()

    var supabaseAnonKey: String
        get() = prefs.getString("supabase_anon", "") ?: ""
        set(v) = prefs.edit().putString("supabase_anon", v.trim()).apply()

    var imgbbKey: String
        get() = prefs.getString("imgbb_key", "") ?: ""
        set(v) = prefs.edit().putString("imgbb_key", v.trim()).apply()

    /** GitHub repositories the user added as custom skills (https URLs), one per line. */
    var customSkills: List<String>
        get() = (prefs.getString("custom_skills", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = prefs.edit().putString("custom_skills", v.distinct().joinToString("\n")).apply()

    /** Every provider that is ready to use. The model switcher next to the chat box shows exactly these. */
    fun configuredModels(): List<ConfiguredModel> = buildList {
        if (apiKey(ProviderType.ANTHROPIC).isNotBlank() && model(ProviderType.ANTHROPIC).isNotBlank())
            add(ConfiguredModel(ProviderType.ANTHROPIC, "Claude", model(ProviderType.ANTHROPIC)))
        if (apiKey(ProviderType.GEMINI).isNotBlank() && model(ProviderType.GEMINI).isNotBlank())
            add(ConfiguredModel(ProviderType.GEMINI, "Gemini", model(ProviderType.GEMINI)))
        val keyless = openAiBaseUrl.startsWith("http://")
        if ((apiKey(ProviderType.OPENAI).isNotBlank() || keyless) && model(ProviderType.OPENAI).isNotBlank())
            add(ConfiguredModel(ProviderType.OPENAI, "OpenAI-compatible", model(ProviderType.OPENAI)))
        if (localModelName.isNotBlank())
            add(ConfiguredModel(ProviderType.LOCAL, "On-device", localModelName))
    }

    /** Returns a human readable problem with the current setup, or null when ready to chat. */
    fun configProblem(): String? = when (provider) {
        ProviderType.ANTHROPIC -> when {
            apiKey(ProviderType.ANTHROPIC).isBlank() -> "Add your Anthropic API key in Settings."
            model(ProviderType.ANTHROPIC).isBlank() -> "Choose a model in Settings."
            else -> null
        }
        ProviderType.GEMINI -> when {
            apiKey(ProviderType.GEMINI).isBlank() -> "Add your Gemini API key in Settings."
            model(ProviderType.GEMINI).isBlank() -> "Choose a Gemini model in Settings (the list loads after you paste the key)."
            else -> null
        }
        ProviderType.OPENAI -> when {
            openAiBaseUrl.isBlank() -> "Set the base URL in Settings."
            model(ProviderType.OPENAI).isBlank() -> "Choose a model in Settings."
            else -> null
        }
        ProviderType.LOCAL -> if (localModelName.isBlank()) "Import or download a local .gguf model in Settings." else null
    }

    private fun defaultModel(p: ProviderType) = when (p) {
        ProviderType.ANTHROPIC -> "claude-sonnet-5-5"
        else -> ""
    }

    private companion object {
        const val FILE = "codepress_secure"

        fun create(context: Context): SharedPreferences {
            fun open(): SharedPreferences {
                val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                return EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try {
                open()
            } catch (e: Exception) {
                // Happens when the Keystore key was lost (e.g. after a restore). Start fresh instead of crashing.
                context.deleteSharedPreferences(FILE)
                open()
            }
        }
    }
}

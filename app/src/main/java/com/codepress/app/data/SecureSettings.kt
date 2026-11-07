package com.codepress.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class ProviderType(val label: String) {
    ANTHROPIC("Anthropic (Claude)"),
    OPENAI("OpenAI-compatible"),
    LOCAL("On-device model (llama.cpp)"),
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

    var openAiBaseUrl: String
        get() = prefs.getString("openai_base", OPENAI_PRESETS.first().baseUrl) ?: OPENAI_PRESETS.first().baseUrl
        set(v) = prefs.edit().putString("openai_base", v.trim()).apply()

    /** File name (inside the app's models folder) of the selected GGUF model. */
    var localModelName: String
        get() = prefs.getString("local_model", "") ?: ""
        set(v) = prefs.edit().putString("local_model", v).apply()

    var autoApprove: Boolean
        get() = prefs.getBoolean("auto_approve", false)
        set(v) = prefs.edit().putBoolean("auto_approve", v).apply()

    /** Returns a human readable problem with the current setup, or null when ready to chat. */
    fun configProblem(): String? = when (provider) {
        ProviderType.ANTHROPIC -> when {
            apiKey(ProviderType.ANTHROPIC).isBlank() -> "Add your Anthropic API key in Settings."
            model(ProviderType.ANTHROPIC).isBlank() -> "Choose a model in Settings."
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

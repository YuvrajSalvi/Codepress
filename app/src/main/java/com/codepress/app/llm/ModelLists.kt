package com.codepress.app.llm

import com.codepress.app.agent.parseObject
import com.codepress.app.agent.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.IOException

/** Asks a provider which models the API key can use, so nobody has to remember model names. */
object ModelLists {

    suspend fun anthropic(apiKey: String): List<String> {
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/models?limit=100")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .build()
        return fetch(request)
    }

    suspend fun openAi(baseUrl: String, apiKey: String): List<String> {
        val builder = Request.Builder().url(baseUrl.trimEnd('/') + "/models")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return fetch(builder.build())
    }

    private suspend fun fetch(request: Request): List<String> = withContext(Dispatchers.IO) {
        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, body)
            val data = parseObject(body)?.get("data") as? JsonArray ?: throw IOException("Unexpected response from the model list")
            data.mapNotNull { (it as? JsonObject)?.str("id") }.sorted()
        }
    }
}

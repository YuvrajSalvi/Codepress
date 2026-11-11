package com.codepress.app.llm

import com.codepress.app.agent.parseObject
import com.codepress.app.agent.str
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, val body: String) : IOException(friendly(code, body)) {
    companion object {
        fun friendly(code: Int, body: String): String {
            val detail = errorMessage(body)
            val prefix = when (code) {
                401, 403 -> "The server rejected the API key (HTTP $code)"
                404 -> "Not found (HTTP 404). Check the model name and base URL"
                429 -> "Rate limit or quota reached (HTTP 429)"
                in 500..599 -> "The provider had a server error (HTTP $code)"
                else -> "Request failed (HTTP $code)"
            }
            return if (detail.isBlank()) prefix else "$prefix: $detail"
        }
    }
}

/** Pulls a readable message out of the usual `{"error": {"message": ...}}` bodies. */
fun errorMessage(body: String): String {
    val obj = parseObject(body) ?: return body.take(300)
    val err = obj["error"]
    val msg = when (err) {
        is JsonObject -> err.str("message")
        is JsonPrimitive -> err.contentOrNull
        else -> null
    } ?: obj.str("message")
    return (msg ?: body).take(400)
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    val JSON = "application/json".toMediaType()

    fun jsonBody(text: String) = text.toRequestBody(JSON)

    /**
     * Sends [request] and feeds the Server-Sent-Events response to [onEvent] as it arrives.
     * Cancelling the coroutine cancels the HTTP call.
     */
    suspend fun streamSse(request: Request, onEvent: (event: String?, data: String) -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) {
                                throw ApiException(response.code, response.body?.string().orEmpty())
                            }
                            val source = response.body?.source() ?: throw IOException("Empty response")
                            val parser = SseParser(onEvent)
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                parser.line(line)
                            }
                            parser.finish()
                            if (cont.isActive) cont.resume(Unit)
                        } catch (e: Throwable) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                }
            })
        }
}

/** Minimal Server-Sent-Events line parser. */
class SseParser(private val onEvent: (event: String?, data: String) -> Unit) {
    private var event: String? = null
    private val data = StringBuilder()

    fun line(l: String) {
        if (l.isEmpty()) {
            flush()
            return
        }
        if (l.startsWith(":")) return
        val i = l.indexOf(':')
        val field = if (i < 0) l else l.substring(0, i)
        var value = if (i < 0) "" else l.substring(i + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
    }

    fun finish() = flush()

    private fun flush() {
        if (data.isNotEmpty()) onEvent(event, data.toString())
        event = null
        data.clear()
    }
}

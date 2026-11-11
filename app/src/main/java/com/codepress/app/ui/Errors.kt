package com.codepress.app.ui

import com.codepress.app.llm.ApiException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException

/** Short, non-technical messages. Technical details are never shown in the chat. */
private fun apiMessage(e: ApiException): String {
    val t = e.body.lowercase()
    fun has(vararg w: String) = w.any { it in t }
    return when {
        e.code == 401 || e.code == 403 -> "This API key was not accepted. Check it in Settings."
        e.code == 404 -> "This model is not available. Pick another model."
        e.code == 503 || e.code == 529 || e.code == 502 || has("overloaded", "high demand", "unavailable") ->
            "This model is very busy right now. Try another model."
        e.code == 402 || has("quota", "billing", "insufficient", "credit", "exhausted", "tokens per day") ->
            "This model's limit is finished. Try another model or try later."
        e.code == 429 -> "Too many requests right now. Wait a little or try another model."
        e.code == 413 || (e.code == 400 && has("context", "too long", "maximum", "token")) ->
            "This chat is too long for the model. Start a new chat or try another model."
        e.code in 500..599 -> "This model had a problem. Try another model."
        else -> "Something went wrong. Try again or switch the model."
    }
}

fun friendlyError(e: Throwable): String = when (e) {
    is ApiException -> apiMessage(e)
    is UnknownHostException -> "Cannot reach the server. Check your internet connection."
    is ConnectException -> "Cannot connect to the server. Check the base URL and your network."
    is SocketTimeoutException -> "The request timed out. Try again."
    is TimeoutCancellationException -> "The local model engine did not start in time."
    is OutOfMemoryError -> "Not enough memory. Close other apps or use a smaller model."
    is UnsatisfiedLinkError -> "The on-device engine could not be loaded on this phone (${e.message})."
    else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}

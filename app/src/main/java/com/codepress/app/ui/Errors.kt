package com.codepress.app.ui

import com.codepress.app.llm.ApiException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException

fun friendlyError(e: Throwable): String = when (e) {
    is ApiException -> e.message ?: "Request failed"
    is UnknownHostException -> "Cannot reach the server. Check your internet connection."
    is ConnectException -> "Cannot connect to the server. Check the base URL and your network."
    is SocketTimeoutException -> "The request timed out. Try again."
    is TimeoutCancellationException -> "The local model engine did not start in time."
    is OutOfMemoryError -> "Not enough memory. Close other apps or use a smaller model."
    is UnsatisfiedLinkError -> "The on-device engine could not be loaded on this phone (${e.message})."
    else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}

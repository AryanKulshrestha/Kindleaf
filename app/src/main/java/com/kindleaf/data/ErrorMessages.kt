package com.kindleaf.data

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns any failure into a short message that is safe to show a customer.
 * Supabase exception messages include the request URL and headers, so never show `message` directly.
 */
fun Throwable.userMessage(fallback: String = "Something went wrong. Please try again."): String = when (this) {
    is CancellationException -> fallback
    is RestException -> error.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(200) ?: fallback
    is HttpRequestTimeoutException -> "The request timed out. Check your connection and try again."
    is UnknownHostException, is HttpRequestException -> "No internet connection. Please try again."
    is IOException -> "Network error. Please try again."
    is EdgeFunctionException -> message ?: fallback
    else -> message?.lineSequence()?.firstOrNull { it.isNotBlank() }
        ?.takeUnless { it.contains("URL:") || it.contains("http", ignoreCase = true) }
        ?.take(200)
        ?: fallback
}

/** Error returned by a Kindleaf Edge Function as `{ "error": "..." }`. */
class EdgeFunctionException(message: String, val statusCode: Int) : Exception(message)

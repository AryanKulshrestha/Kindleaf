package com.kindleaf.data.remote

import com.kindleaf.data.EdgeFunctionException
import com.kindleaf.data.SupabaseHelper
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Calls Kindleaf's Supabase Edge Functions as the signed-in user; errors come back as `{ "error": "..." }`. */
internal object EdgeFunctions {

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Message used when the gateway says the function doesn't exist (not deployed yet). */
    const val NOT_DEPLOYED = "This service isn't set up yet. Please try again later."

    private val http by lazy {
        HttpClient(Android) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
            }
        }
    }

    suspend fun invoke(function: String, payload: JsonObject): String {
        val token = SupabaseHelper.client.auth.currentAccessTokenOrNull()
            ?: throw EdgeFunctionException("Please sign in again", 401)
        val response = http.post("${SupabaseHelper.url.trimEnd('/')}/functions/v1/$function") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("apikey", SupabaseHelper.publishableKey)
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val message = runCatching {
                json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull()
            throw EdgeFunctionException(
                message ?: when (response.status.value) {
                    404 -> NOT_DEPLOYED
                    else -> "Service error (${response.status.value}). Please try again."
                },
                response.status.value,
            )
        }
        return text
    }
}

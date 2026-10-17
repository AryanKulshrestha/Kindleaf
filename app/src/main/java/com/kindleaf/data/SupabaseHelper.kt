package com.kindleaf.data

import com.kindleaf.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import kotlinx.serialization.json.Json

/**
 * Central Supabase client for Kindleaf.
 * Credentials are loaded from [.env] via BuildConfig at compile time.
 *
 * encodeDefaults=true is required so boolean flags like is_active/is_enabled=true
 * are included in PATCH bodies (kotlinx skips default values otherwise, which
 * prevents re-enabling toggles after disable).
 */
object SupabaseHelper {

    val url: String
        get() = BuildConfig.SUPABASE_URL

    val publishableKey: String
        get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    val isConfigured: Boolean
        get() = url.isNotBlank() &&
            publishableKey.isNotBlank() &&
            !url.contains("your-project") &&
            publishableKey != "your-publishable-key"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }

    val client: SupabaseClient by lazy {
        require(isConfigured) {
            "Supabase is not configured. Add credentials to the project .env file."
        }
        createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = publishableKey,
        ) {
            defaultSerializer = KotlinXSerializer(json)
            install(Auth)
            install(Postgrest)
            install(Storage)
        }
    }
}

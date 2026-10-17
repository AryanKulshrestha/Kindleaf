package com.kindleaf.data.remote

import com.kindleaf.data.SupabaseHelper
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object AuthRepository {

    private val auth get() = SupabaseHelper.client.auth

    fun currentUser(): UserInfo? = auth.currentUserOrNull()

    fun isLoggedIn(): Boolean = currentUser() != null

    suspend fun signUp(name: String, email: String, password: String) {
        auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                put("name", name.trim())
                put("full_name", name.trim())
            }
        }
        val user = auth.currentUserOrNull()
        if (user != null) {
            ProfilesRepository.ensureProfile(
                userId = user.id,
                name = name.trim(),
                email = email.trim()
            )
        }
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String) {
        auth.signInWith(IDToken) {
            this.idToken = idToken
            provider = Google
            nonce = rawNonce
        }
        val user = auth.currentUserOrNull() ?: return
        val metaName = user.userMetadata?.get("full_name")?.toString()?.trim('"')
            ?: user.userMetadata?.get("name")?.toString()?.trim('"')
            ?: user.email?.substringBefore("@")
            ?: "User"
        ProfilesRepository.ensureProfile(
            userId = user.id,
            name = metaName,
            email = user.email.orEmpty()
        )
    }

    suspend fun signOut() {
        auth.signOut()
    }

    /**
     * Sends a password-reset email containing a 6-digit code.
     * The Supabase "Reset Password" email template must include {{ .Token }} (see supabase/README.md).
     */
    suspend fun sendPasswordResetCode(email: String) {
        auth.resetPasswordForEmail(email.trim(), redirectUrl = null)
    }

    /** Verifies the emailed recovery code (signs the user in) and sets the new password. */
    suspend fun resetPasswordWithCode(email: String, code: String, newPassword: String) {
        auth.verifyEmailOtp(type = OtpType.Email.RECOVERY, email = email.trim(), token = code.trim())
        auth.updateUser { password = newPassword }
    }

    suspend fun changePassword(newPassword: String) {
        auth.updateUser { password = newPassword }
    }

    /**
     * Starts an email change. Supabase emails a confirmation link; the profile email updates
     * automatically (database trigger) once the change is confirmed.
     */
    suspend fun changeEmail(newEmail: String) {
        auth.updateUser { email = newEmail.trim() }
    }

    /**
     * Permanently deletes the signed-in customer's account (Google Play requirement) via the
     * `delete-account` Edge Function. It refuses while orders, refunds or returns are in progress.
     */
    suspend fun deleteAccount() {
        EdgeFunctions.invoke("delete-account", kotlinx.serialization.json.JsonObject(emptyMap()))
        runCatching { auth.signOut() }
        runCatching { auth.clearSession() }
    }

    suspend fun restoreSession(): Boolean {
        runCatching { auth.awaitInitialization() }
        return isLoggedIn()
    }
}

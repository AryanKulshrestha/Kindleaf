package com.kindleaf.auth

import com.kindleaf.data.userMessage
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.RestException

/** Maps sign-in / sign-up / password errors to short, customer-friendly messages. */
object AuthErrors {

    fun userMessage(throwable: Throwable): String {
        val code = when (throwable) {
            is RestException -> throwable.error
            else -> ""
        }.lowercase()
        val raw = buildString {
            append(code).append(' ')
            if (throwable is AuthRestException) append(throwable.errorDescription).append(' ')
            append(throwable.message.orEmpty())
            throwable.cause?.message?.let { append(' ').append(it) }
        }

        fun has(vararg needles: String) = needles.any { raw.contains(it, ignoreCase = true) }

        return when {
            has("28444", "Developer console is not set up", "DEVELOPER_ERROR") ->
                "Google Sign-In isn't available right now. Please sign in with email and password."

            has("GetCredentialCancellation") || (throwable::class.simpleName?.contains("Cancellation") == true) ->
                "Google Sign-In was cancelled."

            has("No credentials available", "NoCredential") ->
                "No Google account found on this device. Add one in Settings or sign in with email."

            has("email_not_confirmed", "Email not confirmed") ->
                "Please confirm your email first. Check your inbox (and spam folder) for the confirmation link."

            has("invalid_credentials", "Invalid login credentials") ->
                "Wrong email or password."

            has("user_already_exists", "email_exists", "User already registered", "already been registered") ->
                "An account with this email already exists. Try signing in instead."

            has("same_password", "should be different from the old password") ->
                "Your new password must be different from your current password."

            has("weak_password", "Password should") ->
                "That password is too weak. Use at least 8 characters with letters and numbers."

            has("otp_expired", "Token has expired", "otp_disabled", "invalid_otp", "Token has expired or is invalid") ->
                "That code is invalid or has expired. Request a new code and try again."

            has("over_email_send_rate_limit", "over_request_rate_limit", "rate limit", "too many requests", "For security purposes") ->
                "Too many attempts. Please wait a minute and try again."

            has("email_address_invalid", "Unable to validate email address", "invalid format") ->
                "Please enter a valid email address."

            has("signup_disabled", "Signups not allowed") ->
                "New sign-ups are currently disabled. Please try again later."

            has("user_banned") ->
                "This account has been suspended. Please contact support."

            has("reauthentication_needed", "session_not_found", "Auth session missing") ->
                "Your session has expired. Please sign out and sign in again."

            has("provider_disabled") ->
                "This sign-in method is not enabled right now."

            has("invalid_grant", "nonce", "Bad Request") && has("google", "id_token", "nonce") ->
                "Google sign-in failed. Please try again or use email and password."

            throwable is AuthRestException ->
                throwable.errorDescription.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160)
                    ?: "Something went wrong. Please try again."

            else -> throwable.userMessage()
        }
    }
}

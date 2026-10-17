package com.kindleaf.ui.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Validation rules shared by the auth, profile and address screens (they mirror the DB constraints). */
object AccountValidation {

    private val emailRegex = Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val mobileRegex = Regex("^[6-9][0-9]{9}$")
    private val pincodeRegex = Regex("^[1-9][0-9]{5}$")

    const val MIN_PASSWORD_LENGTH = 8

    fun isValidEmail(email: String): Boolean = emailRegex.matches(email.trim())

    fun emailError(email: String): String? = when {
        email.isBlank() -> "Enter your email"
        !isValidEmail(email) -> "Enter a valid email address"
        else -> null
    }

    /** Null when the password satisfies: 8+ characters, at least one letter and one digit. */
    fun passwordError(password: String): String? = when {
        password.length < MIN_PASSWORD_LENGTH -> "Use at least $MIN_PASSWORD_LENGTH characters"
        password.none { it.isLetter() } -> "Include at least one letter"
        password.none { it.isDigit() } -> "Include at least one number"
        else -> null
    }

    fun confirmError(password: String, confirm: String): String? =
        if (confirm.isNotEmpty() && confirm != password) "Passwords don't match" else null

    /** Strips spaces, dashes, brackets and a leading +91 / 91 / 0 from an Indian mobile number. */
    fun normalizePhone(raw: String): String {
        var digits = raw.filter { it.isDigit() || it == '+' }
        if (digits.startsWith("+91")) digits = digits.removePrefix("+91")
        digits = digits.filter { it.isDigit() }
        if (digits.length == 12 && digits.startsWith("91")) digits = digits.removePrefix("91")
        if (digits.length == 11 && digits.startsWith("0")) digits = digits.removePrefix("0")
        return digits
    }

    fun isValidMobile(raw: String): Boolean = mobileRegex.matches(normalizePhone(raw))

    fun phoneError(raw: String, required: Boolean = true): String? = when {
        raw.isBlank() -> if (required) "Enter a mobile number" else null
        !isValidMobile(raw) -> "Enter a valid 10-digit mobile number starting with 6-9"
        else -> null
    }

    fun isValidPincode(pincode: String): Boolean = pincodeRegex.matches(pincode.trim())

    fun pincodeError(pincode: String): String? = when {
        pincode.isBlank() -> "Enter a pincode"
        !isValidPincode(pincode) -> "Enter a valid 6-digit pincode"
        else -> null
    }

    /** Indian states and union territories for the address form. */
    val indianStates = listOf(
        "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa", "Gujarat",
        "Haryana", "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala", "Madhya Pradesh",
        "Maharashtra", "Manipur", "Meghalaya", "Mizoram", "Nagaland", "Odisha", "Punjab",
        "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana", "Tripura", "Uttar Pradesh",
        "Uttarakhand", "West Bengal",
        "Andaman and Nicobar Islands", "Chandigarh", "Dadra and Nagar Haveli and Daman and Diu",
        "Delhi", "Jammu and Kashmir", "Ladakh", "Lakshadweep", "Puducherry",
    )
}

/** True when the user signed up only with Google (no email/password identity), so password changes don't apply. */
fun UserInfo.isGoogleOnly(): Boolean {
    val meta = appMetadata ?: return false
    val providers = (meta["providers"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    if (providers.isNotEmpty()) return providers.none { it == "email" }
    return (meta["provider"] as? JsonPrimitive)?.contentOrNull == "google"
}

/** Opens [url] in the browser; returns false when nothing can handle it. */
fun openUrl(context: Context, url: String): Boolean {
    if (url.isBlank()) return false
    val fixed = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    return startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(fixed)))
}

fun startSafely(context: Context, intent: Intent): Boolean = try {
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/** Password field with a show/hide toggle, inline error and supporting text. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password"
                )
            }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        isError = error != null,
        supportingText = when {
            error != null -> ({ Text(error) })
            supportingText != null -> ({ Text(supportingText) })
            else -> null
        },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = keyboardActions,
    )
}

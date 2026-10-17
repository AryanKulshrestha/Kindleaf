package com.kindleaf.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.kindleaf.auth.AuthErrors
import com.kindleaf.auth.GoogleAuthHelper
import com.kindleaf.data.SessionManager
import com.kindleaf.data.StoreSettingsStore
import com.kindleaf.data.remote.AuthRepository
import com.kindleaf.ui.account.AccountValidation
import com.kindleaf.ui.account.PasswordField
import com.kindleaf.ui.theme.PrimaryGreen
import com.kindleaf.ui.theme.SurfaceWhite
import kotlinx.coroutines.launch

@Composable
fun SignupScreen(
    onSignedUp: () -> Unit,
    onGoLogin: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var confirmationSentTo by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { StoreSettingsStore.refresh() }

    val nameError = if (submitted && name.isBlank()) "Enter your name" else null
    val emailError = if (submitted) AccountValidation.emailError(email) else null
    val passwordError = if (submitted) AccountValidation.passwordError(password) else null
    val confirmError = when {
        submitted && confirm.isEmpty() -> "Re-enter your password"
        else -> AccountValidation.confirmError(password, confirm)
    }

    fun runAuth(block: suspend () -> Unit) {
        scope.launch {
            loading = true
            error = null
            try {
                block()
                if (AuthRepository.isLoggedIn()) {
                    SessionManager.bootstrapAfterAuth()
                    onSignedUp()
                } else {
                    // Email confirmation is on: Supabase created the account but returned no session.
                    confirmationSentTo = email.trim()
                }
            } catch (e: Exception) {
                error = AuthErrors.userMessage(e)
            } finally {
                loading = false
            }
        }
    }

    fun submit() {
        submitted = true
        focusManager.clearFocus()
        val valid = name.isNotBlank() &&
            AccountValidation.emailError(email) == null &&
            AccountValidation.passwordError(password) == null &&
            confirm == password && agreed
        if (!valid) return
        runAuth { AuthRepository.signUp(name.trim(), email.trim(), password) }
    }

    val sentTo = confirmationSentTo
    if (sentTo != null) {
        ConfirmEmailSent(email = sentTo, onGoLogin = onGoLogin)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text(
            text = "Create account",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Join Kindleaf for wellness teas",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text("Full name") },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            isError = nameError != null,
            supportingText = nameError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next
            )
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; error = null },
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
            isError = emailError != null,
            supportingText = emailError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
        )
        Spacer(Modifier.height(8.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it; error = null },
            label = "Password",
            error = passwordError,
            supportingText = "At least 8 characters, with a letter and a number",
            enabled = !loading,
        )
        Spacer(Modifier.height(8.dp))
        PasswordField(
            value = confirm,
            onValueChange = { confirm = it; error = null },
            label = "Confirm password",
            error = confirmError,
            enabled = !loading,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
        )

        Spacer(Modifier.height(8.dp))
        TermsAgreementRow(
            checked = agreed,
            onCheckedChange = { agreed = it },
            enabled = !loading
        )
        if (submitted && !agreed) {
            Text(
                "Please accept the Terms and Privacy Policy to continue",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { submit() },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen),
            enabled = !loading && agreed
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = SurfaceWhite,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Sign Up", color = SurfaceWhite)
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                val activity = context as? Activity
                if (activity == null) {
                    error = "Unable to start Google Sign-In"
                    return@OutlinedButton
                }
                runAuth {
                    val result = GoogleAuthHelper.signIn(activity).getOrElse { throw it }
                    AuthRepository.signInWithGoogleIdToken(result.idToken, result.rawNonce)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = !loading
        ) {
            Text("Sign up with Google")
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onGoLogin, enabled = !loading) {
            Text("Already have an account? Sign in", color = PrimaryGreen)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TermsAgreementRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    val settings = StoreSettingsStore.settings
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = PrimaryGreen, textDecoration = TextDecoration.Underline)
    )
    val text = buildAnnotatedString {
        append("I agree to the ")
        if (settings.termsUrl.isNotBlank()) {
            withLink(LinkAnnotation.Url(settings.termsUrl, linkStyle)) { append("Terms") }
        } else {
            append("Terms")
        }
        append(" and ")
        if (settings.privacyPolicyUrl.isNotBlank()) {
            withLink(LinkAnnotation.Url(settings.privacyPolicyUrl, linkStyle)) { append("Privacy Policy") }
        } else {
            append("Privacy Policy")
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = PrimaryGreen),
            modifier = Modifier.semantics {
                contentDescription = "I agree to the Terms and Privacy Policy"
            }
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ConfirmEmailSent(email: String, onGoLogin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(
            Icons.Outlined.MarkEmailRead,
            contentDescription = null,
            tint = PrimaryGreen,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Confirm your email",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "We've sent a confirmation link to $email. Open it to activate your account, then sign in. " +
                "If you don't see it, check your spam folder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGoLogin,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen)
        ) {
            Text("Back to sign in", color = SurfaceWhite)
        }
    }
}

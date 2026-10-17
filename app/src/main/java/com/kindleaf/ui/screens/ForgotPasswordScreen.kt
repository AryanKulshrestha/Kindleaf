package com.kindleaf.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kindleaf.auth.AuthErrors
import com.kindleaf.data.SessionManager
import com.kindleaf.data.remote.AuthRepository
import com.kindleaf.ui.account.AccountValidation
import com.kindleaf.ui.account.PasswordField
import com.kindleaf.ui.theme.PrimaryGreen
import com.kindleaf.ui.theme.SurfaceWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RESEND_COOLDOWN_MS = 60_000L
private const val CODE_LENGTH = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onPasswordReset: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var codeSent by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var emailSubmitted by rememberSaveable { mutableStateOf(false) }
    var resetSubmitted by rememberSaveable { mutableStateOf(false) }
    var resendAvailableAt by rememberSaveable { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    // Ticks while the resend cooldown is active.
    LaunchedEffect(resendAvailableAt) {
        now = System.currentTimeMillis()
        while (now < resendAvailableAt) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val secondsLeft = ((resendAvailableAt - now).coerceAtLeast(0) + 999) / 1000

    fun sendCode(isResend: Boolean) {
        emailSubmitted = true
        focusManager.clearFocus()
        if (AccountValidation.emailError(email) != null) return
        scope.launch {
            loading = true
            error = null
            info = null
            try {
                AuthRepository.sendPasswordResetCode(email.trim())
                codeSent = true
                resendAvailableAt = System.currentTimeMillis() + RESEND_COOLDOWN_MS
                info = if (isResend) "We've sent a new code to ${email.trim()}."
                else "If an account exists for ${email.trim()}, you'll get an email with a 6-digit code."
            } catch (e: Exception) {
                error = AuthErrors.userMessage(e)
            } finally {
                loading = false
            }
        }
    }

    val codeError = if (resetSubmitted && code.length != CODE_LENGTH) "Enter the 6-digit code from the email" else null
    val passwordError = if (resetSubmitted) AccountValidation.passwordError(newPassword) else null
    val confirmError = when {
        resetSubmitted && confirm.isEmpty() -> "Re-enter your new password"
        else -> AccountValidation.confirmError(newPassword, confirm)
    }

    fun resetPassword() {
        resetSubmitted = true
        focusManager.clearFocus()
        val valid = code.length == CODE_LENGTH &&
            AccountValidation.passwordError(newPassword) == null &&
            confirm == newPassword
        if (!valid) return
        scope.launch {
            loading = true
            error = null
            info = null
            try {
                AuthRepository.resetPasswordWithCode(email.trim(), code, newPassword)
                // Verifying the code signs the user in; ask them to sign in again with the new password.
                SessionManager.logout()
                Toast.makeText(
                    context,
                    "Password updated. Sign in with your new password.",
                    Toast.LENGTH_LONG
                ).show()
                onPasswordReset()
            } catch (e: Exception) {
                error = AuthErrors.userMessage(e)
            } finally {
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Reset password",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (codeSent && !loading) {
                            codeSent = false
                            error = null
                            info = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            if (!codeSent) {
                Text(
                    "Forgot your password?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Enter the email you signed up with and we'll send you a 6-digit code to reset it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                val emailError = if (emailSubmitted) AccountValidation.emailError(email) else null
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendCode(isResend = false) })
                )
            } else {
                Text(
                    "Check your email",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Enter the 6-digit code we sent to ${email.trim()} and choose a new password.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { input ->
                        code = input.filter { it.isDigit() }.take(CODE_LENGTH)
                        error = null
                    },
                    label = { Text("6-digit code") },
                    leadingIcon = { Icon(Icons.Outlined.Pin, contentDescription = null) },
                    isError = codeError != null,
                    supportingText = codeError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next)
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = newPassword,
                    onValueChange = { newPassword = it; error = null },
                    label = "New password",
                    error = passwordError,
                    supportingText = "At least 8 characters, with a letter and a number",
                    enabled = !loading
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = "Confirm new password",
                    error = confirmError,
                    enabled = !loading,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { resetPassword() })
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { codeSent = false; error = null; info = null },
                        enabled = !loading
                    ) {
                        Text("Change email", color = PrimaryGreen)
                    }
                    TextButton(
                        onClick = { sendCode(isResend = true) },
                        enabled = !loading && secondsLeft == 0L
                    ) {
                        Text(
                            if (secondsLeft > 0) "Resend code in ${secondsLeft}s" else "Resend code",
                            color = if (secondsLeft > 0) MaterialTheme.colorScheme.onSurfaceVariant else PrimaryGreen
                        )
                    }
                }
            }

            info?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = PrimaryGreen, style = MaterialTheme.typography.bodySmall)
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { if (codeSent) resetPassword() else sendCode(isResend = false) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen),
                enabled = !loading
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = SurfaceWhite,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(if (codeSent) "Reset password" else "Send code", color = SurfaceWhite)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

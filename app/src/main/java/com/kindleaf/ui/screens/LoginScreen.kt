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
import com.kindleaf.auth.GoogleAuthHelper
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.SessionManager
import com.kindleaf.data.remote.AuthRepository
import com.kindleaf.ui.account.AccountValidation
import com.kindleaf.ui.account.PasswordField
import com.kindleaf.ui.theme.PrimaryGreen
import com.kindleaf.ui.theme.SurfaceWhite
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    onLoggedIn: (isAdmin: Boolean) -> Unit,
    onGoSignup: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val emailError = if (submitted) AccountValidation.emailError(email) else null
    val passwordError = if (submitted && password.isEmpty()) "Enter your password" else null

    fun runAuth(block: suspend () -> Unit) {
        scope.launch {
            loading = true
            error = null
            try {
                block()
                SessionManager.bootstrapAfterAuth()
                // The role comes only from the database profile; admins land on the admin panel.
                onLoggedIn(AppStateManager.userProfile.value.isAdmin)
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
        if (AccountValidation.emailError(email) != null || password.isEmpty()) return
        runAuth { AuthRepository.signIn(email.trim(), password) }
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
        Spacer(Modifier.height(48.dp))
        Text(
            text = "Kindleaf",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = PrimaryGreen
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Welcome back",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Sign in to continue",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

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
            enabled = !loading,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { submit() })
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onForgotPassword, enabled = !loading) {
                Text("Forgot password?", color = PrimaryGreen)
            }
        }

        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { submit() },
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
                Text("Sign In", color = SurfaceWhite)
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                "  OR  ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
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
            Text("Continue with Google")
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onGoSignup, enabled = !loading) {
            Text("New here? Create an account", color = PrimaryGreen)
        }
        Spacer(Modifier.height(32.dp))
    }
}

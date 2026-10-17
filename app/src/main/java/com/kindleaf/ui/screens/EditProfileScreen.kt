package com.kindleaf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kindleaf.auth.AuthErrors
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.remote.AuthRepository
import com.kindleaf.data.remote.ProfilesRepository
import com.kindleaf.data.userMessage
import com.kindleaf.ui.account.AccountValidation
import com.kindleaf.ui.account.PasswordField
import com.kindleaf.ui.account.isGoogleOnly
import com.kindleaf.ui.theme.PrimaryGreen
import com.kindleaf.ui.theme.SurfaceWhite
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    onBack: () -> Unit
) {
    val currentProfile = AppStateManager.userProfile.value
    val authUser = remember { AuthRepository.currentUser() }
    val googleOnly = remember(authUser) { authUser?.isGoogleOnly() == true }
    val pendingEmail = authUser?.newEmail?.takeIf { it.isNotBlank() }

    var name by rememberSaveable { mutableStateOf(currentProfile.name) }
    var phone by rememberSaveable { mutableStateOf(currentProfile.phone) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showEmailDialog by rememberSaveable { mutableStateOf(false) }
    var showPasswordDialog by rememberSaveable { mutableStateOf(false) }
    var emailChangeSentTo by rememberSaveable { mutableStateOf(pendingEmail) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val nameError = if (submitted && name.isBlank()) "Enter your name" else null
    val phoneError = if (submitted) AccountValidation.phoneError(phone, required = false) else null

    val initials = remember(name) {
        name.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifEmpty { "U" }
    }

    fun save() {
        submitted = true
        if (name.isBlank() || AccountValidation.phoneError(phone, required = false) != null) return
        scope.launch {
            saving = true
            error = null
            try {
                val userId = currentProfile.id.ifBlank { null } ?: AuthRepository.currentUser()?.id
                    ?: throw IllegalStateException("Please sign in again to update your profile.")
                val normalizedPhone = if (phone.isBlank()) "" else AccountValidation.normalizePhone(phone)
                val updated = ProfilesRepository.updateProfile(
                    userId = userId,
                    name = name.trim(),
                    phone = normalizedPhone
                )
                AppStateManager.setLoggedInUser(updated)
                phone = updated.phone
                snackbarHostState.showSnackbar("Profile updated")
            } catch (e: Exception) {
                error = e.userMessage("Couldn't update your profile. Please try again.")
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Edit Profile",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(PrimaryGreen),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = SurfaceWhite
                )
            }

            Spacer(Modifier.height(32.dp))

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
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                )
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = phone,
                onValueChange = { input ->
                    phone = input.filter { it.isDigit() || it == '+' || it == ' ' || it == '-' }.take(16)
                    error = null
                },
                label = { Text("Mobile number") },
                leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
                isError = phoneError != null,
                supportingText = { Text(phoneError ?: "10-digit Indian mobile number") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done)
            )

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen),
                enabled = !saving
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = SurfaceWhite,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Save changes", style = MaterialTheme.typography.labelLarge, color = SurfaceWhite)
                }
            }

            Spacer(Modifier.height(32.dp))

            // Sign-in details
            Text(
                "Sign-in details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Email,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Email",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                currentProfile.email.ifBlank { authUser?.email.orEmpty() },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            emailChangeSentTo?.let {
                                Text(
                                    "Pending change to $it. Open the link we emailed to confirm.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PrimaryGreen
                                )
                            }
                        }
                        if (!googleOnly) {
                            TextButton(onClick = { showEmailDialog = true }) {
                                Text("Change", color = PrimaryGreen)
                            }
                        }
                    }
                    if (!googleOnly) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        )
                        Surface(
                            onClick = { showPasswordDialog = true },
                            color = androidx.compose.ui.graphics.Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    "Change password",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Outlined.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Text(
                            "You sign in with Google, so your email and password are managed by your Google account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showEmailDialog) {
        ChangeEmailDialog(
            currentEmail = currentProfile.email,
            onDismiss = { showEmailDialog = false },
            onSent = { newEmail ->
                showEmailDialog = false
                emailChangeSentTo = newEmail
                scope.launch {
                    snackbarHostState.showSnackbar("Confirmation link sent to $newEmail")
                }
            }
        )
    }

    if (showPasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onChanged = {
                showPasswordDialog = false
                scope.launch { snackbarHostState.showSnackbar("Password updated") }
            }
        )
    }
}

@Composable
private fun ChangeEmailDialog(
    currentEmail: String,
    onDismiss: () -> Unit,
    onSent: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var newEmail by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldError = when {
        !submitted -> null
        AccountValidation.emailError(newEmail) != null -> AccountValidation.emailError(newEmail)
        newEmail.trim().equals(currentEmail, ignoreCase = true) -> "This is already your email"
        else -> null
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Change email") },
        text = {
            Column {
                Text(
                    "We'll send a confirmation link to the new address. Your email changes once you open that link.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newEmail,
                    onValueChange = { newEmail = it; error = null },
                    label = { Text("New email") },
                    isError = fieldError != null,
                    supportingText = fieldError?.let { { Text(it) } },
                    singleLine = true,
                    enabled = !sending,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending,
                onClick = {
                    submitted = true
                    if (AccountValidation.emailError(newEmail) != null ||
                        newEmail.trim().equals(currentEmail, ignoreCase = true)
                    ) return@TextButton
                    scope.launch {
                        sending = true
                        error = null
                        try {
                            AuthRepository.changeEmail(newEmail.trim())
                            onSent(newEmail.trim())
                        } catch (e: Exception) {
                            error = AuthErrors.userMessage(e)
                        } finally {
                            sending = false
                        }
                    }
                }
            ) {
                if (sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Send link", color = PrimaryGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") }
        }
    )
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val passwordError = if (submitted) AccountValidation.passwordError(password) else null
    val confirmError = if (submitted && confirm.isEmpty()) "Re-enter your new password"
    else AccountValidation.confirmError(password, confirm)

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Change password") },
        text = {
            Column {
                PasswordField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = "New password",
                    error = passwordError,
                    supportingText = "At least 8 characters, with a letter and a number",
                    enabled = !saving
                )
                Spacer(Modifier.height(4.dp))
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = "Confirm new password",
                    error = confirmError,
                    enabled = !saving,
                    imeAction = ImeAction.Done
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    submitted = true
                    if (AccountValidation.passwordError(password) != null || confirm != password) return@TextButton
                    scope.launch {
                        saving = true
                        error = null
                        try {
                            AuthRepository.changePassword(password)
                            onChanged()
                        } catch (e: Exception) {
                            error = AuthErrors.userMessage(e)
                        } finally {
                            saving = false
                        }
                    }
                }
            ) {
                if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Update", color = PrimaryGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        }
    )
}

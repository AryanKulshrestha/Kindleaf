package com.kindleaf.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.remote.AddressesRepository
import com.kindleaf.data.remote.AuthRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Address
import com.kindleaf.ui.account.AccountValidation
import com.kindleaf.ui.theme.PrimaryGreen
import com.kindleaf.ui.theme.SurfaceWhite
import kotlinx.coroutines.launch

private val addressLabels = listOf("Home", "Work", "Other")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditAddressScreen(
    addressId: String?,
    onBack: () -> Unit
) {
    val existingAddress = addressId?.let { id -> AppStateManager.addresses.find { it.id == id } }
    val isEditing = addressId != null
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadingExisting by remember { mutableStateOf(isEditing && existingAddress == null) }
    var initialized by rememberSaveable { mutableStateOf(!isEditing || existingAddress != null) }
    val isFirstAddress = !isEditing && AppStateManager.addresses.isEmpty()

    var label by rememberSaveable { mutableStateOf(existingAddress?.label?.takeIf { it in addressLabels } ?: "Home") }
    var name by rememberSaveable { mutableStateOf(existingAddress?.name ?: AppStateManager.userProfile.value.name) }
    var addressLine by rememberSaveable { mutableStateOf(existingAddress?.addressLine ?: "") }
    var city by rememberSaveable { mutableStateOf(existingAddress?.city ?: "") }
    var state by rememberSaveable { mutableStateOf(matchState(existingAddress?.state)) }
    var pincode by rememberSaveable { mutableStateOf(existingAddress?.pincode ?: "") }
    var phone by rememberSaveable {
        mutableStateOf(existingAddress?.phone ?: AppStateManager.userProfile.value.phone)
    }
    var isDefault by rememberSaveable { mutableStateOf(existingAddress?.isDefault ?: false) }
    var submitted by rememberSaveable { mutableStateOf(false) }

    // Opened directly (e.g. after process death): load addresses, then fill the form once.
    LaunchedEffect(addressId) {
        if (isEditing && existingAddress == null) {
            val userId = AuthRepository.currentUser()?.id
            if (userId != null) {
                runCatching { AddressesRepository.fetchAddresses(userId) }
                    .onSuccess { AppStateManager.replaceAddresses(it) }
                    .onFailure { error = it.userMessage("Couldn't load this address.") }
            }
            loadingExisting = false
        }
    }
    LaunchedEffect(existingAddress) {
        if (!initialized && existingAddress != null) {
            label = existingAddress.label.takeIf { it in addressLabels } ?: "Other"
            name = existingAddress.name
            addressLine = existingAddress.addressLine
            city = existingAddress.city
            state = matchState(existingAddress.state)
            pincode = existingAddress.pincode
            phone = existingAddress.phone
            isDefault = existingAddress.isDefault
            initialized = true
        }
    }

    val nameError = if (submitted && name.isBlank()) "Enter the recipient's name" else null
    val addressError = if (submitted && addressLine.isBlank()) "Enter house no., street and area" else null
    val cityError = if (submitted && city.isBlank()) "Enter a city" else null
    val stateError = if (submitted && state !in AccountValidation.indianStates) "Select a state" else null
    val pincodeError = if (submitted) AccountValidation.pincodeError(pincode) else null
    val phoneError = if (submitted) AccountValidation.phoneError(phone) else null

    fun save() {
        submitted = true
        val valid = name.isNotBlank() && addressLine.isNotBlank() && city.isNotBlank() &&
            state in AccountValidation.indianStates &&
            AccountValidation.pincodeError(pincode) == null &&
            AccountValidation.phoneError(phone) == null
        if (!valid) {
            error = "Please fix the highlighted fields."
            return
        }
        val userId = AuthRepository.currentUser()?.id
        if (userId == null) {
            error = "Please sign in to save addresses."
            return
        }
        scope.launch {
            saving = true
            error = null
            try {
                val draft = Address(
                    id = existingAddress?.id ?: "",
                    label = label,
                    name = name.trim(),
                    addressLine = addressLine.trim(),
                    city = city.trim(),
                    state = state,
                    pincode = pincode.trim(),
                    phone = AccountValidation.normalizePhone(phone),
                    isDefault = isDefault || isFirstAddress || AppStateManager.addresses.isEmpty()
                )
                if (existingAddress != null) {
                    val updated = AddressesRepository.updateAddress(draft)
                    AppStateManager.updateAddress(updated)
                } else {
                    var created = AddressesRepository.addAddress(userId, draft)
                    if (draft.isDefault && !created.isDefault) {
                        AddressesRepository.setDefaultAddress(userId, created.id)
                        created = created.copy(isDefault = true)
                    }
                    AppStateManager.addAddress(created)
                }
                onBack()
            } catch (e: Exception) {
                error = e.userMessage("Couldn't save the address. Please try again.")
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
                        if (isEditing) "Edit Address" else "Add Address",
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
        }
    ) { padding ->
        if (loadingExisting) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = PrimaryGreen) }
            return@Scaffold
        }
        if (isEditing && existingAddress == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        error ?: "This address no longer exists.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onBack) { Text("Go back") }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            Text(
                "Address type",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                addressLabels.forEach { option ->
                    FilterChip(
                        selected = label == option,
                        onClick = { label = option },
                        label = { Text(option) },
                        leadingIcon = if (label == option) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryGreen,
                            selectedLabelColor = SurfaceWhite,
                            selectedLeadingIconColor = SurfaceWhite
                        )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Full name *") },
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
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = phone,
                onValueChange = { input ->
                    phone = input.filter { it.isDigit() || it == '+' || it == ' ' || it == '-' }.take(16)
                },
                label = { Text("Mobile number *") },
                isError = phoneError != null,
                supportingText = { Text(phoneError ?: "For delivery updates from the courier") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = addressLine,
                onValueChange = { addressLine = it },
                label = { Text("House no., street, area *") },
                isError = addressError != null,
                supportingText = addressError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = false,
                minLines = 2,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )
            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it },
                    label = { Text("City *") },
                    isError = cityError != null,
                    supportingText = cityError?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next
                    )
                )
                OutlinedTextField(
                    value = pincode,
                    onValueChange = { input -> pincode = input.filter { it.isDigit() }.take(6) },
                    label = { Text("Pincode *") },
                    isError = pincodeError != null,
                    supportingText = pincodeError?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                )
            }
            Spacer(Modifier.height(4.dp))

            StateDropdown(
                selected = state,
                onSelected = { state = it },
                error = stateError
            )

            Spacer(Modifier.height(8.dp))

            val defaultLocked = isFirstAddress || existingAddress?.isDefault == true
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = isDefault || defaultLocked,
                    onCheckedChange = { isDefault = it },
                    enabled = !defaultLocked,
                    colors = CheckboxDefaults.colors(checkedColor = PrimaryGreen)
                )
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        "Set as default address",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (isFirstAddress) {
                        Text(
                            "Your first address is used as the default",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

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
                    Text(
                        if (isEditing) "Update address" else "Save address",
                        style = MaterialTheme.typography.labelLarge,
                        color = SurfaceWhite
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Maps a stored state name onto the dropdown list (case-insensitive); unknown values stay as typed. */
private fun matchState(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return AccountValidation.indianStates.firstOrNull { it.equals(value.trim(), ignoreCase = true) } ?: value.trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StateDropdown(
    selected: String,
    onSelected: (String) -> Unit,
    error: String?,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("State / UT *") },
            placeholder = { Text("Select state") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AccountValidation.indianStates.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                    trailingIcon = if (option == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = "Selected", tint = PrimaryGreen) }
                    } else null,
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

package com.kindleaf.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kindleaf.data.ChargesStore
import com.kindleaf.data.remote.ChargeWriteDto
import com.kindleaf.data.remote.ChargesRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Charge
import kotlinx.coroutines.launch

private fun Charge.isDelivery() = key.equals("delivery", ignoreCase = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminChargesScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var charges by remember { mutableStateOf<List<Charge>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var pendingToggle by remember { mutableStateOf<Set<String>>(emptySet()) }

    var editorFor by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Charge?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        if (!refreshing) loading = true
        error = null
        attempt { ChargesRepository.fetchAll() }
            .onSuccess { charges = it }
            .onFailure { error = it.userMessage("Couldn't load charges.") }
        loading = false
        refreshing = false
    }

    /** Keeps the cart's fee estimates in sync after any change. */
    fun syncStore() {
        scope.launch { ChargesStore.refresh() }
    }

    Scaffold(
        topBar = {
            AdminTopBar(
                title = "Charges",
                subtitle = "Delivery and extra fees",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { editorFor = "" }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add charge")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reloadKey++ },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    loading -> item { AdminLoading(Modifier.fillParentMaxSize()) }
                    error != null && charges.isEmpty() -> item {
                        AdminErrorState(error.orEmpty(), onRetry = { reloadKey++ }, modifier = Modifier.fillParentMaxSize())
                    }
                    charges.isEmpty() -> item {
                        AdminEmptyState(
                            "No charges configured. Add a \"delivery\" charge to set the delivery fee.",
                            icon = Icons.Outlined.Payments,
                            modifier = Modifier.fillParentMaxSize()
                        )
                    }
                    else -> {
                        item {
                            Text(
                                "These fees are added at checkout. The server applies the same rules when pricing an order. " +
                                    "Disable a charge to stop applying it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        (actionError ?: error)?.let { msg -> item { AdminInlineError(msg) } }
                        items(charges, key = { it.id }) { charge ->
                            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(charge.name, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                buildString {
                                                    append(rupees(charge.amount))
                                                    charge.freeAbove?.let { append(" · free above ${rupees(it)}") }
                                                    append(" · key: ${charge.key}")
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (charge.description.isNotBlank()) {
                                                Text(
                                                    charge.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        if (charge.id in pendingToggle) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                        } else {
                                            Switch(
                                                checked = charge.isEnabled,
                                                modifier = Modifier.semantics { contentDescription = "${charge.name} enabled" },
                                                onCheckedChange = { enabled ->
                                                    val previous = charges
                                                    charges = charges.map { if (it.id == charge.id) it.copy(isEnabled = enabled) else it }
                                                    pendingToggle = pendingToggle + charge.id
                                                    scope.launch {
                                                        attempt { ChargesRepository.setEnabled(charge.id, enabled) }
                                                            .onSuccess { saved ->
                                                                charges = charges.map { if (it.id == saved.id) saved else it }
                                                                actionError = null
                                                                syncStore()
                                                            }
                                                            .onFailure { e ->
                                                                charges = previous
                                                                actionError = e.userMessage("Couldn't update ${charge.name}.")
                                                            }
                                                        pendingToggle = pendingToggle - charge.id
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (!charge.isEnabled) AdminBadge("Disabled")
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = { editorFor = charge.id }) { Text("Edit") }
                                        if (!charge.isDelivery()) {
                                            IconButton(onClick = { deleteError = null; deleting = charge }) {
                                                Icon(Icons.Outlined.Delete, contentDescription = "Delete ${charge.name}")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editorFor?.let { id ->
        ChargeEditorDialog(
            existing = charges.firstOrNull { it.id == id },
            defaultSortOrder = (charges.maxOfOrNull { it.sortOrder } ?: 0) + 1,
            existingKeys = charges.map { it.key.lowercase() }.toSet(),
            onDismiss = { editorFor = null },
            onSaved = {
                editorFor = null
                reloadKey++
                syncStore()
            }
        )
    }

    deleting?.let { charge ->
        AdminConfirmDialog(
            title = "Delete \"${charge.name}\"?",
            message = "This fee will no longer be added to orders. To pause it instead, switch it off.",
            confirmLabel = "Delete",
            busy = deleteBusy,
            error = deleteError,
            onDismiss = { if (!deleteBusy) deleting = null },
            onConfirm = {
                scope.launch {
                    deleteBusy = true
                    deleteError = null
                    attempt { ChargesRepository.delete(charge.id) }
                        .onSuccess {
                            charges = charges.filterNot { it.id == charge.id }
                            deleting = null
                            syncStore()
                        }
                        .onFailure { deleteError = it.userMessage("Couldn't delete the charge.") }
                    deleteBusy = false
                }
            }
        )
    }
}

@Composable
private fun ChargeEditorDialog(
    existing: Charge?,
    defaultSortOrder: Int,
    existingKeys: Set<String>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var key by rememberSaveable { mutableStateOf(existing?.key.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(existing?.amount?.toString().orEmpty()) }
    var freeAbove by rememberSaveable { mutableStateOf(existing?.freeAbove?.toString().orEmpty()) }
    var description by rememberSaveable { mutableStateOf(existing?.description.orEmpty()) }
    var sortOrder by rememberSaveable { mutableStateOf((existing?.sortOrder ?: defaultSortOrder).toString()) }
    var isEnabled by rememberSaveable { mutableStateOf(existing?.isEnabled ?: true) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val isDelivery = (existing?.key ?: key).equals("delivery", ignoreCase = true)
    val nameError = if (name.isBlank()) "Name is required" else null
    val keyError = when {
        existing != null -> null
        key.isBlank() -> "Key is required"
        key.lowercase() in existingKeys -> "This key is already used"
        else -> null
    }
    val amountError = if (amount.toIntOrNull() == null) "Enter an amount (0 or more)" else null
    val hasErrors = nameError != null || keyError != null || amountError != null

    AdminConfirmDialog(
        title = if (existing == null) "Add charge" else "Edit charge",
        message = if (isDelivery) "The delivery charge is the store's delivery fee." else "An extra fee added to every order.",
        confirmLabel = "Save",
        destructive = false,
        busy = saving,
        error = error,
        onDismiss = { if (!saving) onDismiss() },
        onConfirm = {
            showErrors = true
            if (hasErrors) return@AdminConfirmDialog
            scope.launch {
                saving = true
                error = null
                val dto = ChargeWriteDto(
                    key = (existing?.key ?: key).trim().lowercase(),
                    name = name.trim(),
                    amount = amount.toIntOrNull() ?: 0,
                    isEnabled = isEnabled,
                    freeAbove = freeAbove.toIntOrNull(),
                    description = description.trim(),
                    sortOrder = sortOrder.toIntOrNull() ?: 0,
                )
                attempt {
                    if (existing == null) ChargesRepository.create(dto) else ChargesRepository.update(existing.id, dto)
                }.onSuccess {
                    saving = false
                    onSaved()
                }.onFailure {
                    error = it.userMessage("Couldn't save the charge.")
                    saving = false
                }
            }
        },
        extraContent = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("Display name *") },
                isError = showErrors && nameError != null,
                supportingText = if (showErrors && nameError != null) ({ Text(nameError) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = key,
                onValueChange = { if (existing == null) key = it.lowercase().replace(' ', '_').filter { c -> c.isLetterOrDigit() || c == '_' }.take(40) },
                label = { Text("Key *") },
                enabled = existing == null,
                isError = showErrors && keyError != null,
                supportingText = {
                    Text(if (showErrors && keyError != null) keyError else "e.g. delivery, packaging. Can't be changed later")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter(Char::isDigit).take(6) },
                label = { Text("Amount (₹) *") },
                isError = showErrors && amountError != null,
                supportingText = if (showErrors && amountError != null) ({ Text(amountError) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = freeAbove,
                onValueChange = { freeAbove = it.filter(Char::isDigit).take(7) },
                label = { Text("Free above ₹ (optional)") },
                supportingText = { Text("The charge becomes ₹0 once the subtotal reaches this amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(200) },
                label = { Text("Description") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = sortOrder,
                onValueChange = { sortOrder = it.filter(Char::isDigit).take(4) },
                label = { Text("Position") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
            }
        }
    )
}

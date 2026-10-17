package com.kindleaf.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.remote.AddressesRepository
import com.kindleaf.data.remote.AuthRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Address
import com.kindleaf.ui.theme.PrimaryGreen
import com.kindleaf.ui.theme.SurfaceWhite
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressesScreen(
    onBack: () -> Unit,
    onAddAddress: () -> Unit,
    onEditAddress: (String) -> Unit
) {
    val addresses = AppStateManager.addresses
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(addresses.isEmpty()) }
    var refreshing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        val userId = AuthRepository.currentUser()?.id
        if (userId == null) {
            loadError = "Please sign in to manage your addresses."
            return
        }
        try {
            AppStateManager.replaceAddresses(AddressesRepository.fetchAddresses(userId))
            loadError = null
        } catch (e: Exception) {
            loadError = e.userMessage("Couldn't load your addresses.")
        }
    }

    LaunchedEffect(Unit) {
        load()
        loading = false
    }

    fun setDefault(address: Address) {
        val userId = AuthRepository.currentUser()?.id ?: return
        val snapshot = addresses.toList()
        busyId = address.id
        AppStateManager.setDefaultAddress(address.id)
        scope.launch {
            try {
                AddressesRepository.setDefaultAddress(userId, address.id)
            } catch (e: Exception) {
                AppStateManager.replaceAddresses(snapshot)
                snackbarHostState.showSnackbar(e.userMessage("Couldn't change the default address."))
            } finally {
                busyId = null
            }
        }
    }

    fun delete(addressId: String) {
        val snapshot = addresses.toList()
        busyId = addressId
        AppStateManager.deleteAddress(addressId)
        scope.launch {
            try {
                AddressesRepository.deleteAddress(addressId)
                // The server may promote another address to default; keep local state in sync.
                load()
            } catch (e: Exception) {
                AppStateManager.replaceAddresses(snapshot)
                snackbarHostState.showSnackbar(e.userMessage("Couldn't delete the address."))
            } finally {
                busyId = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "My Addresses",
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddAddress,
                containerColor = PrimaryGreen,
                contentColor = SurfaceWhite,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Address")
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    load()
                    refreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryGreen)
                }

                loadError != null && addresses.isEmpty() -> AddressesMessage(
                    icon = Icons.Outlined.CloudOff,
                    title = "Couldn't load addresses",
                    message = loadError!!,
                    actionLabel = "Retry",
                    onAction = {
                        scope.launch {
                            loading = true
                            load()
                            loading = false
                        }
                    }
                )

                addresses.isEmpty() -> AddressesMessage(
                    icon = Icons.Outlined.LocationOn,
                    title = "No addresses saved",
                    message = "Add a delivery address for faster checkout.",
                    actionLabel = "Add address",
                    onAction = onAddAddress
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(addresses, key = { it.id }) { address ->
                        SavedAddressCard(
                            address = address,
                            busy = busyId == address.id,
                            onEdit = { onEditAddress(address.id) },
                            onDelete = { deleteTargetId = address.id },
                            onSetDefault = { setDefault(address) }
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }

    deleteTargetId?.let { addressId ->
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text("Delete address?") },
            text = { Text("This address will be removed from your account.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTargetId = null
                    delete(addressId)
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetId = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AddressesMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    // Scrollable so pull-to-refresh works on the empty/error state too.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        Spacer(Modifier.height(64.dp))
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onAction) { Text(actionLabel, color = PrimaryGreen) }
    }
}

@Composable
private fun SavedAddressCard(
    address: Address,
    busy: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = if (address.isDefault) CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(PrimaryGreen)
        ) else null
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            address.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    if (address.isDefault) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = PrimaryGreen
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Default",
                                style = MaterialTheme.typography.labelSmall,
                                color = PrimaryGreen,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    IconButton(onClick = onEdit, enabled = !busy) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Edit ${address.label} address",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDelete, enabled = !busy) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Delete ${address.label} address",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Text(
                address.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${address.addressLine}\n${address.city}, ${address.state} - ${address.pincode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                address.phone,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!address.isDefault) {
                TextButton(onClick = onSetDefault, enabled = !busy) {
                    Text("Set as default", color = PrimaryGreen, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

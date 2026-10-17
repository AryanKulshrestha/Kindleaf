package com.kindleaf.ui.admin

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kindleaf.data.remote.OrdersRepository
import com.kindleaf.data.remote.PointsRepository
import com.kindleaf.data.remote.ProfilesRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Order
import com.kindleaf.models.OrderStatusFlow
import com.kindleaf.models.PointsTransaction
import com.kindleaf.models.UserProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private fun UserProfile.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val digits = q.filter { it.isDigit() }
    return name.lowercase().contains(q) ||
        email.lowercase().contains(q) ||
        (digits.length >= 3 && phone.filter { it.isDigit() }.contains(digits))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminCustomersScreen(onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var customers by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var adjusting by remember { mutableStateOf<UserProfile?>(null) }
    // Bumped after a points adjustment so the open ledger reloads.
    var detailRefresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        if (!refreshing) loading = true
        error = null
        attempt { ProfilesRepository.fetchAllProfiles() }
            .onSuccess { all -> customers = all.filter { !it.isAdmin } }
            .onFailure { error = it.userMessage("Couldn't load customers.") }
        loading = false
        refreshing = false
    }

    val visible = customers.filter { it.matches(query) }

    Scaffold(
        topBar = {
            AdminTopBar(
                title = "Customers",
                subtitle = if (customers.isNotEmpty()) "${customers.size} customers" else null,
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AdminSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Name, email or phone",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true; reloadKey++ },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when {
                        loading -> item { AdminLoading(Modifier.fillParentMaxSize()) }
                        error != null && customers.isEmpty() -> item {
                            AdminErrorState(error.orEmpty(), onRetry = { reloadKey++ }, modifier = Modifier.fillParentMaxSize())
                        }
                        customers.isEmpty() -> item {
                            AdminEmptyState("No customers yet", icon = Icons.Outlined.People, modifier = Modifier.fillParentMaxSize())
                        }
                        else -> {
                            error?.let { msg -> item { AdminInlineError(msg) } }
                            if (visible.isEmpty()) {
                                item {
                                    Text(
                                        "No customers match \"$query\"",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 24.dp)
                                    )
                                }
                            }
                            items(visible, key = { it.id }) { customer ->
                                CustomerCard(
                                    customer = customer,
                                    expanded = expandedId == customer.id,
                                    refreshKey = detailRefresh,
                                    onToggle = { expandedId = if (expandedId == customer.id) null else customer.id },
                                    onAdjust = { adjusting = customer }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    adjusting?.let { customer ->
        AdjustPointsDialog(
            customer = customer,
            onDismiss = { adjusting = null },
            onAdjusted = { newBalance, delta ->
                customers = customers.map { if (it.id == customer.id) it.copy(leafPoints = newBalance) else it }
                adjusting = null
                detailRefresh++
                scope.launch {
                    snackbar.showSnackbar(
                        (if (delta > 0) "Added $delta" else "Deducted ${-delta}") + " points. New balance $newBalance."
                    )
                }
            }
        )
    }
}

@Composable
private fun CustomerCard(
    customer: UserProfile,
    expanded: Boolean,
    refreshKey: Int,
    onToggle: () -> Unit,
    onAdjust: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "Hide details" else "Show details", onClick = onToggle)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(customer.name.ifBlank { "Customer" }, fontWeight = FontWeight.SemiBold)
                if (customer.email.isNotBlank()) {
                    Text(customer.email, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    customer.phone.ifBlank { "No phone" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                AdminBadge("${customer.leafPoints} pts", if (customer.leafPoints > 0) AdminTone.Success else AdminTone.Neutral)
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (expanded) {
            HorizontalDivider()
            CustomerDetails(customer = customer, refreshKey = refreshKey, onAdjust = onAdjust)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerDetails(customer: UserProfile, refreshKey: Int, onAdjust: () -> Unit) {
    var tab by rememberSaveable(customer.id) { mutableIntStateOf(0) }
    var ledger by remember(customer.id) { mutableStateOf<List<PointsTransaction>?>(null) }
    var orders by remember(customer.id) { mutableStateOf<List<Order>?>(null) }
    var error by remember(customer.id) { mutableStateOf<String?>(null) }
    var retryKey by remember(customer.id) { mutableIntStateOf(0) }

    LaunchedEffect(customer.id, refreshKey, retryKey) {
        error = null
        attempt {
            coroutineScope {
                val l = async { PointsRepository.fetchLedger(customer.id) }
                val o = async { OrdersRepository.fetchForUser(customer.id, limit = 20) }
                l.await() to o.await()
            }
        }.onSuccess { (l, o) ->
            ledger = l
            orders = o
        }.onFailure { error = it.userMessage("Couldn't load this customer's history.") }
    }

    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onAdjust, modifier = Modifier.fillMaxWidth()) {
            Text("Adjust Leaf Points")
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Points history") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Orders") })
        }
        when {
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retryKey++ }) { Text("Retry") }
            }
            (if (tab == 0) ledger else orders) == null -> Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            tab == 0 -> {
                val rows = ledger.orEmpty()
                if (rows.isEmpty()) {
                    Text("No points activity yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                rows.take(30).forEach { tx ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(tx.reasonLabel, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                            if (tx.note.isNotBlank()) {
                                Text(tx.note, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(tx.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                (if (tx.delta > 0) "+" else "") + tx.delta,
                                fontWeight = FontWeight.SemiBold,
                                color = if (tx.delta > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text("Bal ${tx.balanceAfter}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            else -> {
                val rows = orders.orEmpty()
                if (rows.isEmpty()) {
                    Text("No orders yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                rows.forEach { order ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("#${order.shortId}", fontWeight = FontWeight.Medium)
                            Text(
                                "${order.placedDate} · ${order.itemCount} item${if (order.itemCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(rupees(order.totalPrice), fontWeight = FontWeight.SemiBold)
                            AdminBadge(OrderStatusFlow.label(order.status), orderStatusTone(order.status))
                        }
                    }
                }
                if (rows.size >= 20) {
                    Text(
                        "Showing the latest 20 orders. Search the Orders tab for older ones.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AdjustPointsDialog(
    customer: UserProfile,
    onDismiss: () -> Unit,
    onAdjusted: (newBalance: Int, delta: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var add by rememberSaveable { mutableStateOf(true) }
    var amount by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val value = amount.toIntOrNull()
    val amountError = when {
        value == null || value <= 0 -> "Enter a number of points above 0"
        !add && value > customer.leafPoints -> "They only have ${customer.leafPoints} points"
        else -> null
    }
    val reasonError = if (reason.isBlank()) "A reason is required" else null

    AdminConfirmDialog(
        title = "Adjust Leaf Points",
        message = "${customer.name.ifBlank { "Customer" }} has ${customer.leafPoints} points (${rupees(customer.leafPoints / 10)}). " +
            "The change is recorded in their points history and they are notified if points alerts are on.",
        confirmLabel = if (add) "Add points" else "Deduct points",
        destructive = !add,
        busy = saving,
        error = error,
        onDismiss = { if (!saving) onDismiss() },
        onConfirm = {
            showErrors = true
            if (amountError != null || reasonError != null) return@AdminConfirmDialog
            val delta = if (add) value!! else -value!!
            scope.launch {
                saving = true
                error = null
                attempt { PointsRepository.adminAdjust(customer.id, delta, reason.trim()) }
                    .onSuccess { balance ->
                        saving = false
                        onAdjusted(balance, delta)
                    }
                    .onFailure {
                        error = it.userMessage("Couldn't adjust points.")
                        saving = false
                    }
            }
        },
        extraContent = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = add,
                    onClick = { add = true },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    label = { Text("Add") }
                )
                SegmentedButton(
                    selected = !add,
                    onClick = { add = false },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    label = { Text("Deduct") }
                )
            }
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter(Char::isDigit).take(6) },
                label = { Text("Points") },
                isError = showErrors && amountError != null,
                supportingText = {
                    Text(
                        if (showErrors && amountError != null) amountError
                        else value?.let { "= ${rupees(it / 10)} value" } ?: "1 point = ₹0.10"
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it.take(200) },
                label = { Text("Reason *") },
                placeholder = { Text("e.g. Goodwill for delayed delivery") },
                isError = showErrors && reasonError != null,
                supportingText = if (showErrors && reasonError != null) ({ Text(reasonError) }) else ({ Text("Shown to the customer") }),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(2.dp))
        }
    )
}

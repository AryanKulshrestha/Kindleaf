package com.kindleaf.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Payment
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.remote.OrdersRepository
import com.kindleaf.data.remote.formatDateTime
import com.kindleaf.data.userMessage
import com.kindleaf.models.Order
import com.kindleaf.models.OrderStatusFlow
import com.kindleaf.ui.orders.CenteredMessage
import com.kindleaf.ui.orders.OrderItemThumb
import com.kindleaf.ui.orders.OrderStatusChip
import com.kindleaf.ui.orders.currentUserIdOrNull
import kotlinx.coroutines.launch

private enum class OrderFilter(val label: String) {
    ALL("All"), ACTIVE("Active"), DELIVERED("Delivered"), CANCELLED("Cancelled");

    fun matches(order: Order): Boolean = when (this) {
        ALL -> true
        ACTIVE -> order.status !in setOf("delivered", "cancelled", "returned")
        DELIVERED -> order.status == "delivered" || order.status == "returned"
        CANCELLED -> order.status == "cancelled"
    }
}

/** "My Orders" — the customer's order history. Tapping a row opens the order detail screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderTrackingScreen(
    onBack: () -> Unit,
    onOpenOrder: (String) -> Unit,
    onStartShopping: () -> Unit,
) {
    val orders = AppStateManager.orders
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(OrderFilter.ALL) }

    suspend fun load() {
        val userId = currentUserIdOrNull()
        if (userId == null) {
            error = "Please sign in to see your orders."
            return
        }
        try {
            AppStateManager.replaceOrders(OrdersRepository.fetchForUser(userId))
            error = null
        } catch (e: Exception) {
            val message = e.userMessage("Couldn't load your orders.")
            error = message
            if (orders.isNotEmpty()) scope.launch { snackbar.showSnackbar(message) }
        }
    }

    LaunchedEffect(Unit) {
        load()
        initialLoading = false
    }

    val visible = orders.filter { filter.matches(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "My Orders",
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
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
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
                initialLoading && orders.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                orders.isEmpty() && error != null -> CenteredMessage(
                    icon = Icons.Outlined.CloudOff,
                    title = "Couldn't load orders",
                    message = error.orEmpty(),
                    actionLabel = "Retry",
                    onAction = {
                        scope.launch {
                            initialLoading = true
                            load()
                            initialLoading = false
                        }
                    },
                    isError = true,
                )

                orders.isEmpty() -> CenteredMessage(
                    icon = Icons.Outlined.Inventory2,
                    title = "No orders yet",
                    message = "When you place an order, you can track it here.",
                    actionLabel = "Start shopping",
                    onAction = onStartShopping,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "filters") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(OrderFilter.entries) { f ->
                                val count = orders.count { f.matches(it) }
                                FilterChip(
                                    selected = filter == f,
                                    onClick = { filter = f },
                                    label = { Text(if (f == OrderFilter.ALL) f.label else "${f.label} ($count)") },
                                )
                            }
                        }
                    }
                    if (visible.isEmpty()) {
                        item(key = "empty-filter") {
                            Text(
                                "No ${filter.label.lowercase()} orders.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 32.dp).fillMaxWidth()
                            )
                        }
                    }
                    items(visible, key = { it.id }) { order ->
                        OrderListCard(order = order, onClick = { onOpenOrder(order.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderListCard(order: Order, onClick: () -> Unit) {
    val awaiting = order.isAwaitingPayment
    val accent = MaterialTheme.colorScheme.tertiary
    val itemsLabel = if (order.itemCount == 1) "1 item" else "${order.itemCount} items"

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("Order ${order.shortId}, ${order.product.name}, $itemsLabel, total ₹${order.totalPrice}, ")
                    append("placed ${order.placedDate}, status ${OrderStatusFlow.label(order.status)}")
                    if (awaiting) append(". Payment pending, tap to pay now")
                }
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = if (awaiting) BorderStroke(1.5.dp, accent) else null,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrderItemThumb(order.product.imageUrl, contentDescription = null, size = 60.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        order.product.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "$itemsLabel · ₹${order.totalPrice}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "#${order.shortId} · ${order.placedDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OrderStatusChip(order.status)
                order.refundStatusLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (awaiting) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = accent.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Payment, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        val expiry = formatDateTime(order.expiresAt)
                        Text(
                            if (expiry.isNotBlank()) "Payment pending · Pay now before $expiry"
                            else "Payment pending · Tap to pay now",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

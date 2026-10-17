package com.kindleaf.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.StoreSettingsStore
import com.kindleaf.data.remote.OrdersRepository
import com.kindleaf.data.remote.ReturnsRepository
import com.kindleaf.data.remote.formatDateTime
import com.kindleaf.data.remote.parseTimestamp
import com.kindleaf.data.userMessage
import com.kindleaf.models.Order
import com.kindleaf.models.OrderLineItem
import com.kindleaf.models.OrderStatusEvent
import com.kindleaf.models.OrderStatusFlow
import com.kindleaf.models.ReturnRequest
import com.kindleaf.ui.components.OrderTimeline
import com.kindleaf.ui.orders.CancelOrderDialog
import com.kindleaf.ui.orders.CenteredMessage
import com.kindleaf.ui.orders.InvoicePdf
import com.kindleaf.ui.orders.OrderItemThumb
import com.kindleaf.ui.orders.OrderStatusChip
import com.kindleaf.ui.orders.PriceRow
import com.kindleaf.ui.orders.REFUND_LEAF_POINTS
import com.kindleaf.ui.orders.ReturnRequestDialog
import com.kindleaf.ui.orders.SectionCard
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    orderId: String,
    onBack: () -> Unit,
    onPayNow: (String) -> Unit,
    onWriteReview: (String) -> Unit,
    onGetHelp: (String) -> Unit,
    onProductClick: (String) -> Unit,
    onReorderDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    // Show the cached copy immediately while the fresh one loads.
    var order by remember(orderId) { mutableStateOf(AppStateManager.orders.find { it.id == orderId }) }
    var events by remember(orderId) { mutableStateOf<List<OrderStatusEvent>>(emptyList()) }
    var returns by remember(orderId) { mutableStateOf<List<ReturnRequest>>(emptyList()) }
    var returnsError by remember(orderId) { mutableStateOf(false) }
    var loaded by remember(orderId) { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var notFound by remember(orderId) { mutableStateOf(false) }
    var error by remember(orderId) { mutableStateOf<String?>(null) }

    var showCancel by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var showReturn by remember { mutableStateOf(false) }
    var requestingReturn by remember { mutableStateOf(false) }
    var returnError by remember { mutableStateOf<String?>(null) }
    var makingInvoice by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }

    suspend fun load() {
        try {
            val fetched = OrdersRepository.fetchById(orderId)
            if (fetched == null) {
                notFound = true
            } else {
                notFound = false
                order = fetched
                AppStateManager.upsertOrder(fetched)
            }
            error = null
        } catch (e: Exception) {
            val message = e.userMessage("Couldn't load this order.")
            error = message
            if (order != null) scope.launch { snackbar.showSnackbar(message) }
        }
        runCatching { OrdersRepository.fetchStatusEvents(orderId) }.onSuccess { events = it }
        val current = order
        if (current != null && (current.status == "delivered" || current.status == "returned")) {
            runCatching { ReturnsRepository.fetchForOrder(orderId) }
                .onSuccess { returns = it; returnsError = false }
                .onFailure { returnsError = true }
        }
        loaded = true
    }

    // Loads on first show and again whenever the user comes back (e.g. from payment or support).
    LifecycleResumeEffect(orderId) {
        val job = scope.launch { load() }
        onPauseOrDispose { job.cancel() }
    }

    val current = order

    if (current != null) {
        if (showCancel) {
            CancelOrderDialog(
                order = current,
                busy = cancelling,
                onDismiss = { showCancel = false },
                onConfirm = { choice ->
                    scope.launch {
                        cancelling = true
                        try {
                            val updated = AppStateManager.cancelOrder(current.id, choice)
                            order = updated
                            showCancel = false
                            runCatching { OrdersRepository.fetchStatusEvents(orderId) }.onSuccess { events = it }
                            val message = when {
                                choice == REFUND_LEAF_POINTS ->
                                    "Order cancelled. ${current.leafPointsRefundAmount} Leaf Points credited."
                                current.needsRefundChoice ->
                                    "Order cancelled. Your refund will arrive in 5–7 working days."
                                else -> "Order cancelled."
                            }
                            scope.launch { snackbar.showSnackbar(message) }
                        } catch (e: Exception) {
                            showCancel = false
                            val message = e.userMessage("Couldn't cancel this order.")
                            scope.launch { snackbar.showSnackbar(message) }
                        } finally {
                            cancelling = false
                        }
                    }
                }
            )
        }
        if (showReturn) {
            ReturnRequestDialog(
                order = current,
                returnWindowDays = StoreSettingsStore.settings.returnWindowDays,
                busy = requestingReturn,
                error = returnError,
                onDismiss = { showReturn = false; returnError = null },
                onSubmit = { reason, details, choice ->
                    scope.launch {
                        requestingReturn = true
                        try {
                            val created = ReturnsRepository.request(current.id, reason, details, choice)
                            returns = listOf(created) + returns.filterNot { it.id == created.id }
                            showReturn = false
                            returnError = null
                            scope.launch { snackbar.showSnackbar("Return requested. We'll update you soon.") }
                        } catch (e: Exception) {
                            returnError = e.userMessage("Couldn't request a return.")
                        } finally {
                            requestingReturn = false
                        }
                    }
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        current?.let { "Order #${it.shortId}" } ?: "Order details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onGetHelp(orderId) }) {
                        Icon(Icons.Outlined.SupportAgent, contentDescription = "Get help with this order")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
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
                current == null && !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                notFound -> CenteredMessage(
                    icon = Icons.Outlined.SearchOff,
                    title = "Order not found",
                    message = "This order doesn't exist or isn't linked to your account.",
                    actionLabel = "Go back",
                    onAction = onBack,
                )

                current == null -> CenteredMessage(
                    icon = Icons.Outlined.CloudOff,
                    title = "Couldn't load this order",
                    message = error ?: "Please try again.",
                    actionLabel = "Retry",
                    onAction = {
                        scope.launch {
                            loaded = false
                            load()
                        }
                    },
                    isError = true,
                )

                else -> OrderDetailContent(
                    order = current,
                    events = events,
                    returns = returns,
                    returnsError = returnsError,
                    makingInvoice = makingInvoice,
                    reordering = reordering,
                    onPayNow = { onPayNow(current.id) },
                    onCancel = { showCancel = true },
                    onRequestReturn = { showReturn = true },
                    onWriteReview = onWriteReview,
                    onProductClick = onProductClick,
                    onGetHelp = { onGetHelp(current.id) },
                    onCopyTracking = { number ->
                        clipboard.setText(AnnotatedString(number))
                        scope.launch { snackbar.showSnackbar("Tracking number copied") }
                    },
                    onInvoice = {
                        scope.launch {
                            makingInvoice = true
                            try {
                                val file = InvoicePdf.generate(context, current)
                                InvoicePdf.openOrShare(context, file)
                            } catch (e: Exception) {
                                val message = e.userMessage("Couldn't create the invoice.")
                                scope.launch { snackbar.showSnackbar(message) }
                            } finally {
                                makingInvoice = false
                            }
                        }
                    },
                    onReorder = {
                        scope.launch {
                            reordering = true
                            val unavailable = mutableListOf<String>()
                            var added = 0
                            try {
                                for (item in current.lineItems) {
                                    val product = item.itemId?.let { CatalogStore.loadProduct(it) }
                                    if (product == null || !product.isInStock) {
                                        unavailable += item.name
                                        continue
                                    }
                                    if (AppStateManager.addToCart(product, item.quantity)) added++
                                    else unavailable += item.name
                                }
                            } finally {
                                reordering = false
                            }
                            when {
                                added == 0 -> snackbar.showSnackbar(
                                    if (current.lineItems.isEmpty()) "Nothing to reorder."
                                    else "These items are unavailable or already at the maximum in your cart."
                                )
                                unavailable.isEmpty() -> onReorderDone()
                                else -> {
                                    val result = snackbar.showSnackbar(
                                        message = "Added $added item(s). Unavailable: ${unavailable.joinToString(", ")}",
                                        actionLabel = "View cart",
                                        withDismissAction = true,
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) onReorderDone()
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun OrderDetailContent(
    order: Order,
    events: List<OrderStatusEvent>,
    returns: List<ReturnRequest>,
    returnsError: Boolean,
    makingInvoice: Boolean,
    reordering: Boolean,
    onPayNow: () -> Unit,
    onCancel: () -> Unit,
    onRequestReturn: () -> Unit,
    onWriteReview: (String) -> Unit,
    onProductClick: (String) -> Unit,
    onGetHelp: () -> Unit,
    onCopyTracking: (String) -> Unit,
    onInvoice: () -> Unit,
    onReorder: () -> Unit,
) {
    val settings = StoreSettingsStore.settings
    val delivered = order.status == "delivered"
    val deliveredAt = parseTimestamp(order.deliveredAt)
    val returnDeadline = deliveredAt?.plus(Duration.ofDays(settings.returnWindowDays.toLong()))
    val withinReturnWindow = delivered && returnDeadline != null && Instant.now().isBefore(returnDeadline)
    val latestReturn = returns.firstOrNull()
    val canRequestReturn = withinReturnWindow && (latestReturn == null || latestReturn.status == "rejected")
    val timestamps = remember(order.statuses, events, order.createdAt) { timelineTimestamps(order, events) }
    val lineItems = order.lineItems

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        item(key = "header") {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Placed on ${formatDateTime(order.createdAt).ifBlank { order.placedDate }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "₹${order.totalPrice} · ${if (order.itemCount == 1) "1 item" else "${order.itemCount} items"}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OrderStatusChip(order.status)
                }
                if (order.isAwaitingPayment) {
                    Spacer(Modifier.height(12.dp))
                    val expiry = formatDateTime(order.expiresAt)
                    Text(
                        if (expiry.isNotBlank()) "Complete payment before $expiry or the order will be cancelled automatically."
                        else "Complete payment to confirm this order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onPayNow,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Outlined.Payment, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pay now · ₹${order.totalPrice}")
                    }
                }
            }
        }

        // Timeline
        item(key = "timeline") {
            SectionCard(title = "Order status") {
                OrderTimeline(statuses = order.statuses, timestamps = timestamps)
                if (order.status !in setOf("delivered", "cancelled", "returned", "pending_payment") &&
                    order.estimatedDelivery.isNotBlank()
                ) {
                    Spacer(Modifier.height(12.dp))
                    InfoLine(Icons.Outlined.CalendarMonth, "Estimated delivery: ${order.estimatedDelivery}")
                }
                if (delivered && deliveredAt != null) {
                    Spacer(Modifier.height(12.dp))
                    InfoLine(Icons.Outlined.CheckCircle, "Delivered on ${formatDateTime(order.deliveredAt)}")
                }
            }
        }

        // Tracking
        if (!order.trackingNumber.isNullOrBlank() || !order.courier.isNullOrBlank()) {
            item(key = "tracking") {
                SectionCard(title = "Shipment") {
                    order.courier?.takeIf { it.isNotBlank() }?.let {
                        Text("Courier: $it", style = MaterialTheme.typography.bodyMedium)
                    }
                    order.trackingNumber?.takeIf { it.isNotBlank() }?.let { number ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Tracking no: $number",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onCopyTracking(number) }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy tracking number")
                            }
                        }
                    }
                }
            }
        }

        // Items
        item(key = "items-title") {
            Text(
                "Items in this order",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (lineItems.isEmpty()) {
            item(key = "items-empty") {
                SectionCard {
                    Text(
                        order.product.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Qty ${order.quantity}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(lineItems, key = { "line-" + it.id.ifBlank { it.name } }) { item ->
            LineItemCard(
                item = item,
                canReview = delivered,
                onClick = item.itemId?.let { id -> { onProductClick(id) } },
                onWriteReview = item.itemId?.let { id -> { onWriteReview(id) } },
            )
        }

        // Price breakdown
        item(key = "price") {
            SectionCard(title = "Price details") {
                PriceRow("Subtotal", "₹${order.subtotal}")
                PriceRow("Delivery fee", if (order.deliveryFee == 0) "Free" else "₹${order.deliveryFee}")
                if (order.extraCharges > 0) PriceRow("Other charges", "₹${order.extraCharges}")
                if (order.discount > 0) {
                    val code = order.couponCode?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    PriceRow("Coupon discount$code", "−₹${order.discount}", valueColor = MaterialTheme.colorScheme.primary)
                }
                if (order.pointsDiscount > 0) {
                    PriceRow(
                        "Leaf Points (${order.pointsRedeemed} pts)",
                        "−₹${order.pointsDiscount}",
                        valueColor = MaterialTheme.colorScheme.primary
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                )
                PriceRow("Total", "₹${order.totalPrice}", emphasize = true)
            }
        }

        // Payment
        item(key = "payment") {
            SectionCard(title = "Payment") {
                PriceRow("Method", order.paymentMethod.ifBlank { "—" })
                PriceRow(
                    "Status",
                    order.paymentStatusLabel,
                    valueColor = when (order.paymentStatus) {
                        "paid" -> MaterialTheme.colorScheme.primary
                        "failed" -> MaterialTheme.colorScheme.error
                        else -> androidx.compose.ui.graphics.Color.Unspecified
                    }
                )
                order.refundStatusLabel?.let { label ->
                    PriceRow(
                        "Refund",
                        if (order.refundAmount > 0) "$label · ₹${order.refundAmount}" else label
                    )
                }
                if (delivered && order.pointsEarned > 0) {
                    Spacer(Modifier.height(6.dp))
                    InfoLine(Icons.Outlined.Eco, "You earned ${order.pointsEarned} Leaf Points on this order")
                }
            }
        }

        // Address
        item(key = "address") {
            val a = order.deliveryAddress
            SectionCard(title = "Delivery address") {
                if (a.name.isNotBlank()) {
                    Text(a.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    a.singleLine.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (a.phone.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    InfoLine(Icons.Outlined.Phone, a.phone)
                }
            }
        }

        // Returns
        if (latestReturn != null || returnsError) {
            item(key = "return-status") {
                SectionCard(title = "Return") {
                    if (latestReturn != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardReturn,
                                contentDescription = null,
                                tint = when (latestReturn.status) {
                                    "approved" -> MaterialTheme.colorScheme.primary
                                    "rejected" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.tertiary
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                latestReturn.statusLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            "Reason: ${latestReturn.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Refund to: ${if (latestReturn.refundChoice == REFUND_LEAF_POINTS) "Leaf Points" else if (order.isCod) "Bank transfer / UPI" else "Original payment method"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Requested ${formatDateTime(latestReturn.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (latestReturn.adminNote.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Note from Kindleaf: ${latestReturn.adminNote}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        Text(
                            "Couldn't load return status. Pull down to retry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Actions
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (canRequestReturn && !returnsError) {
                    OutlinedButton(
                        onClick = onRequestReturn,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardReturn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (returnDeadline != null) "Request return (until ${formatDateTime(returnDeadline.toString())})"
                            else "Request return"
                        )
                    }
                }
                if (order.status != "pending_payment") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (lineItems.isNotEmpty()) {
                            OutlinedButton(
                                onClick = onReorder,
                                enabled = !reordering,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                if (reordering) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Outlined.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text("Reorder")
                            }
                        }
                        if (InvoicePdf.isAvailable(order)) {
                            OutlinedButton(
                                onClick = onInvoice,
                                enabled = !makingInvoice,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                if (makingInvoice) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Outlined.Receipt, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text("Invoice")
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = onGetHelp,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Outlined.SupportAgent, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Need help?")
                }
                if (order.canCancel) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Outlined.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel order")
                    }
                }
            }
        }
        item(key = "bottom-space") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun LineItemCard(
    item: OrderLineItem,
    canReview: Boolean,
    onClick: (() -> Unit)?,
    onWriteReview: (() -> Unit)?,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onClick != null) Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = "View product",
                            onClick = onClick
                        ) else Modifier
                    )
                    .padding(12.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = buildString {
                            append(item.name)
                            if (item.size.isNotBlank()) append(", ${item.size}")
                            append(", ${item.quantity} × ₹${item.price}, ₹${item.lineTotal}")
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                OrderItemThumb(item.imageUrl, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.size.isNotBlank()) {
                        Text(
                            item.size,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${item.quantity} × ₹${item.price}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "₹${item.lineTotal}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            if (canReview && onWriteReview != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                TextButton(
                    onClick = onWriteReview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Icon(Icons.Outlined.RateReview, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Write a review")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Formatted time per timeline step, from the order's status events (latest event per status). */
private fun timelineTimestamps(order: Order, events: List<OrderStatusEvent>): List<String?> {
    val latestByStatus = events.groupBy { it.status }.mapValues { (_, list) -> list.last().createdAt }
    val singleKey = when (order.status) {
        "pending_payment", "cancelled", "returned" -> order.status
        else -> null
    }
    return order.statuses.map { step ->
        val key = singleKey ?: OrderStatusFlow.steps.find { it.second == step.title }?.first
        if (!step.isCompleted && !step.isCurrent) return@map null
        val iso = key?.let { latestByStatus[it] } ?: when (key) {
            "placed", "pending_payment" -> order.createdAt
            "delivered" -> order.deliveredAt
            "cancelled" -> order.cancelledAt
            else -> null
        }
        formatDateTime(iso).ifBlank { null }
    }
}

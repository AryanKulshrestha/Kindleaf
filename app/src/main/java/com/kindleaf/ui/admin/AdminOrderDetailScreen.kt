package com.kindleaf.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kindleaf.data.remote.OrdersRepository
import com.kindleaf.data.remote.PaymentsRepository
import com.kindleaf.data.remote.ProfilesRepository
import com.kindleaf.data.remote.formatDateTime
import com.kindleaf.data.userMessage
import com.kindleaf.models.Order
import com.kindleaf.models.OrderStatusEvent
import com.kindleaf.models.OrderStatusFlow
import com.kindleaf.models.UserProfile
import kotlinx.coroutines.launch

private val ADMIN_CANCELLABLE = setOf("pending_payment", "placed", "confirmed", "packed")
private val REFUND_ACTIONABLE = setOf("pending", "processing", "failed")

private sealed interface OrderDialog {
    data class Advance(val target: String) : OrderDialog
    data object Tracking : OrderDialog
    data object Cancel : OrderDialog
    data object RazorpayRefund : OrderDialog
    data object ManualRefund : OrderDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminOrderDetailScreen(
    orderId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var order by remember { mutableStateOf<Order?>(null) }
    var events by remember { mutableStateOf<List<OrderStatusEvent>>(emptyList()) }
    var eventsError by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var notFound by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var dialog by remember { mutableStateOf<OrderDialog?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }

    suspend fun reloadEvents() {
        attempt { OrdersRepository.fetchStatusEvents(orderId) }
            .onSuccess { events = it; eventsError = null }
            .onFailure { eventsError = it.userMessage("Couldn't load the status history.") }
    }

    LaunchedEffect(orderId, reloadKey) {
        if (!refreshing) loading = true
        loadError = null
        attempt { OrdersRepository.fetchById(orderId) }
            .onSuccess { found ->
                order = found
                notFound = found == null
                if (found != null) {
                    reloadEvents()
                    if (found.userId.isNotBlank() && profile?.id != found.userId) {
                        profile = attempt { ProfilesRepository.fetchProfile(found.userId) }.getOrNull()
                    }
                }
            }
            .onFailure { loadError = it.userMessage("Couldn't load this order.") }
        loading = false
        refreshing = false
    }

    fun closeDialog() {
        if (!busy) {
            dialog = null
            dialogError = null
        }
    }

    fun runAction(successMessage: String, block: suspend () -> Order) {
        scope.launch {
            busy = true
            dialogError = null
            attempt { block() }
                .onSuccess { updated ->
                    order = updated
                    busy = false
                    dialog = null
                    reloadEvents()
                    snackbar.showSnackbar(successMessage)
                }
                .onFailure {
                    dialogError = it.userMessage("That didn't work. Please try again.")
                    busy = false
                }
        }
    }

    Scaffold(
        topBar = {
            AdminTopBar(
                title = order?.let { "Order #${it.shortId}" } ?: "Order",
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; reloadKey++ },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val current = order
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    loading && current == null -> item { AdminLoading(Modifier.fillParentMaxSize()) }
                    loadError != null && current == null -> item {
                        AdminErrorState(loadError.orEmpty(), onRetry = { reloadKey++ }, modifier = Modifier.fillParentMaxSize())
                    }
                    notFound || current == null -> item {
                        AdminEmptyState("This order no longer exists.", modifier = Modifier.fillParentMaxSize())
                    }
                    else -> {
                        loadError?.let { msg -> item { AdminInlineError(msg) } }
                        item { HeaderCard(current) }
                        item {
                            ActionsCard(
                                order = current,
                                onAdvance = { target -> dialogError = null; dialog = OrderDialog.Advance(target) },
                                onEditTracking = { dialogError = null; dialog = OrderDialog.Tracking },
                                onCancel = { dialogError = null; dialog = OrderDialog.Cancel },
                            )
                        }
                        if (current.refundStatus != "none") {
                            item {
                                RefundCard(
                                    order = current,
                                    onRazorpay = { dialogError = null; dialog = OrderDialog.RazorpayRefund },
                                    onManual = { dialogError = null; dialog = OrderDialog.ManualRefund },
                                )
                            }
                        }
                        item { CustomerCard(current, profile) }
                        item { ItemsCard(current) }
                        item { PaymentCard(current) }
                        item { HistoryCard(events, eventsError) }
                    }
                }
            }
        }
    }

    val current = order ?: return
    when (val d = dialog) {
        is OrderDialog.Advance -> AdvanceDialog(
            order = current,
            target = d.target,
            busy = busy,
            error = dialogError,
            onDismiss = ::closeDialog,
            onConfirm = { note, courier, tracking ->
                runAction("Order marked as ${OrderStatusFlow.label(d.target)}") {
                    OrdersRepository.adminUpdateStatus(
                        orderId = current.id,
                        status = d.target,
                        trackingNumber = tracking,
                        courier = courier,
                        note = note,
                    )
                }
            }
        )
        OrderDialog.Tracking -> TrackingDialog(
            order = current,
            busy = busy,
            error = dialogError,
            onDismiss = ::closeDialog,
            onConfirm = { courier, tracking ->
                runAction("Tracking details saved") {
                    OrdersRepository.adminUpdateStatus(
                        orderId = current.id,
                        trackingNumber = tracking,
                        courier = courier,
                    )
                }
            }
        )
        OrderDialog.Cancel -> CancelDialog(
            order = current,
            busy = busy,
            error = dialogError,
            onDismiss = ::closeDialog,
            onConfirm = { choice ->
                runAction("Order cancelled") { OrdersRepository.adminCancelOrder(current.id, choice) }
            }
        )
        OrderDialog.RazorpayRefund -> AdminConfirmDialog(
            title = "Refund via Razorpay?",
            message = "${rupees(current.refundAmount.takeIf { it > 0 } ?: current.totalPrice)} will be sent back to the " +
                "customer's original payment method through Razorpay. This cannot be undone.",
            confirmLabel = "Send refund",
            destructive = false,
            busy = busy,
            error = dialogError,
            onDismiss = ::closeDialog,
            onConfirm = {
                runAction("Refund sent to Razorpay") { PaymentsRepository.refundViaRazorpay(current.id) }
            }
        )
        OrderDialog.ManualRefund -> ManualRefundDialog(
            order = current,
            busy = busy,
            error = dialogError,
            onDismiss = ::closeDialog,
            onConfirm = { reference ->
                runAction("Refund marked as completed") {
                    OrdersRepository.completeRefundManually(current.id, reference)
                }
            }
        )
        null -> Unit
    }
}

// ── Cards ──

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderCard(order: Order) {
    DetailCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SelectionContainer {
                    Text("#${order.shortId}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Placed ${formatDateTime(order.createdAt).ifBlank { order.placedDate }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(rupees(order.totalPrice), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AdminBadge(OrderStatusFlow.label(order.status), orderStatusTone(order.status))
            AdminBadge(order.paymentStatusLabel, paymentStatusTone(order.paymentStatus))
            adminRefundLabel(order.refundStatus, order.cancelRefundChoice)?.let { AdminBadge(it, refundTone(order.refundStatus)) }
        }
        if (order.isAwaitingPayment) {
            InfoNote(
                "Awaiting customer payment. This order moves to Placed automatically once the payment is received" +
                    (order.expiresAt?.let { " and expires ${formatDateTime(it)} if unpaid." } ?: ".")
            )
        }
    }
}

@Composable
private fun InfoNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ActionsCard(
    order: Order,
    onAdvance: (String) -> Unit,
    onEditTracking: () -> Unit,
    onCancel: () -> Unit,
) {
    val next = OrderStatusFlow.nextStatus(order.status)
    val canCancel = order.status in ADMIN_CANCELLABLE
    val canEditTracking = order.status !in setOf("pending_payment", "cancelled", "returned")
    if (next == null && !canCancel && !canEditTracking) return

    DetailCard {
        AdminSectionTitle("Actions")
        if (order.isAwaitingPayment) {
            Text(
                "Status can't be advanced until the customer pays.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (next != null) {
            Button(onClick = { onAdvance(next) }, modifier = Modifier.fillMaxWidth()) {
                Text("Mark as ${OrderStatusFlow.label(next)}")
            }
        }
        if (order.status == "shipped") {
            OutlinedButton(onClick = { onAdvance("delivered") }, modifier = Modifier.fillMaxWidth()) {
                Text("Mark as Delivered")
            }
        }
        if (canEditTracking) {
            OutlinedButton(onClick = onEditTracking, modifier = Modifier.fillMaxWidth()) {
                Text(if (order.trackingNumber.isNullOrBlank()) "Add tracking details" else "Edit tracking details")
            }
        }
        if (canCancel) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel order", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RefundCard(order: Order, onRazorpay: () -> Unit, onManual: () -> Unit) {
    val amount = order.refundAmount.takeIf { it > 0 } ?: order.totalPrice
    val actionable = order.refundStatus in REFUND_ACTIONABLE
    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = if (actionable) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AdminSectionTitle("Refund")
            AdminKeyValue("Status", adminRefundLabel(order.refundStatus, order.cancelRefundChoice) ?: order.refundStatus)
            AdminKeyValue("Refund to", refundChoiceLabel(order.cancelRefundChoice))
            AdminKeyValue(
                "Amount",
                if (order.cancelRefundChoice == "leaf_points") "${rupees(amount)} (${amount * 10} points)" else rupees(amount)
            )
            if (order.refundStatus == "failed") {
                Text(
                    "The last refund attempt failed. Retry through Razorpay or refund by hand and mark it below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (actionable) {
                Spacer(Modifier.height(4.dp))
                // "processing" means Razorpay already accepted the refund; the webhook will complete it.
                if (order.razorpayPaymentId != null && order.refundStatus != "processing") {
                    Button(onClick = onRazorpay, modifier = Modifier.fillMaxWidth()) {
                        Text("Refund ${rupees(amount)} via Razorpay")
                    }
                }
                if (order.refundStatus == "processing") {
                    Text(
                        "Refund sent to Razorpay. It will be marked completed automatically once the bank confirms.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
                    Text("Mark refunded manually")
                }
            }
        }
    }
}

@Composable
private fun CustomerCard(order: Order, profile: UserProfile?) {
    DetailCard {
        AdminSectionTitle("Customer")
        when {
            order.userId.isBlank() -> Text(
                "No linked account (deleted or guest)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            profile == null -> Text(
                "Account details unavailable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> SelectionContainer {
                Column {
                    AdminKeyValue("Name", profile.name.ifBlank { "—" })
                    AdminKeyValue("Email", profile.email.ifBlank { "—" })
                    AdminKeyValue("Phone", profile.phone.ifBlank { "—" })
                    AdminKeyValue("Leaf Points", "${profile.leafPoints}")
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Deliver to", fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            Column {
                val address = order.deliveryAddress
                if (address.name.isNotBlank()) Text(address.name)
                if (address.singleLine.isNotBlank()) Text(address.singleLine, style = MaterialTheme.typography.bodyMedium)
                if (address.phone.isNotBlank()) Text("Phone ${address.phone}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!order.courier.isNullOrBlank() || !order.trackingNumber.isNullOrBlank() || order.estimatedDelivery.isNotBlank() || order.deliveredAt != null) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Shipping", fontWeight = FontWeight.SemiBold)
            SelectionContainer {
                Column {
                    order.courier?.takeIf { it.isNotBlank() }?.let { AdminKeyValue("Courier", it) }
                    order.trackingNumber?.takeIf { it.isNotBlank() }?.let { AdminKeyValue("Tracking no.", it) }
                    if (order.estimatedDelivery.isNotBlank()) AdminKeyValue("Estimated delivery", order.estimatedDelivery)
                    order.deliveredAt?.let { AdminKeyValue("Delivered", formatDateTime(it)) }
                }
            }
        }
    }
}

@Composable
private fun ItemsCard(order: Order) {
    DetailCard {
        AdminSectionTitle("Items (${order.itemCount})")
        if (order.lineItems.isEmpty()) {
            Text("No line items recorded", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        order.lineItems.forEachIndexed { index, line ->
            if (index > 0) HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(line.name, fontWeight = FontWeight.Medium)
                    Text(
                        listOf(line.size, "${line.quantity} × ${rupees(line.price)}").filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(rupees(line.lineTotal), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PaymentCard(order: Order) {
    DetailCard {
        AdminSectionTitle("Payment")
        AdminKeyValue("Subtotal", rupees(order.subtotal))
        AdminKeyValue("Delivery", if (order.deliveryFee == 0) "Free" else rupees(order.deliveryFee))
        if (order.extraCharges != 0) AdminKeyValue("Extra charges", rupees(order.extraCharges))
        if (order.discount > 0) {
            AdminKeyValue(
                "Coupon" + (order.couponCode?.let { " ($it)" } ?: ""),
                "-${rupees(order.discount)}",
                valueColor = MaterialTheme.colorScheme.primary
            )
        }
        if (order.pointsDiscount > 0) {
            AdminKeyValue(
                "Leaf Points (${order.pointsRedeemed} pts)",
                "-${rupees(order.pointsDiscount)}",
                valueColor = MaterialTheme.colorScheme.primary
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        AdminKeyValue("Total", rupees(order.totalPrice), emphasize = true)
        Spacer(Modifier.height(4.dp))
        AdminKeyValue("Method", order.paymentMethod.ifBlank { if (order.isCod) "Cash on Delivery" else "—" })
        AdminKeyValue("Status", order.paymentStatusLabel)
        order.paidAt?.let { AdminKeyValue("Paid", formatDateTime(it)) }
        if (order.pointsEarned > 0) AdminKeyValue("Points earned", "${order.pointsEarned}")
        SelectionContainer {
            Column {
                order.razorpayPaymentId?.let { AdminKeyValue("Razorpay payment", it) }
                order.razorpayOrderId?.let { AdminKeyValue("Razorpay order", it) }
                AdminKeyValue("Order ID", order.id)
            }
        }
    }
}

@Composable
private fun HistoryCard(events: List<OrderStatusEvent>, error: String?) {
    DetailCard {
        AdminSectionTitle("Status history")
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else if (events.isEmpty()) {
            Text("No status changes recorded yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        events.asReversed().forEach { event ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Spacer(
                    Modifier
                        .padding(top = 6.dp)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(OrderStatusFlow.label(event.status), fontWeight = FontWeight.Medium)
                    Text(event.createdAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (event.note.isNotBlank()) {
                        Text(event.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ── Dialogs ──

@Composable
private fun AdvanceDialog(
    order: Order,
    target: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (note: String?, courier: String?, tracking: String?) -> Unit,
) {
    val shipping = target == "shipped"
    var note by rememberSaveable { mutableStateOf("") }
    var courier by rememberSaveable { mutableStateOf(order.courier.orEmpty()) }
    var tracking by rememberSaveable { mutableStateOf(order.trackingNumber.orEmpty()) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val courierMissing = shipping && courier.isBlank()
    val trackingMissing = shipping && tracking.isBlank()

    AdminConfirmDialog(
        title = "Mark as ${OrderStatusFlow.label(target)}?",
        message = "The customer is notified automatically.",
        confirmLabel = "Update",
        destructive = false,
        busy = busy,
        error = error,
        onDismiss = onDismiss,
        onConfirm = {
            showErrors = true
            if (courierMissing || trackingMissing) return@AdminConfirmDialog
            onConfirm(
                note.trim().ifBlank { null },
                courier.trim().ifBlank { null },
                tracking.trim().ifBlank { null },
            )
        },
        extraContent = {
            if (shipping) {
                OutlinedTextField(
                    value = courier,
                    onValueChange = { courier = it },
                    label = { Text("Courier") },
                    singleLine = true,
                    isError = showErrors && courierMissing,
                    supportingText = if (showErrors && courierMissing) ({ Text("Enter the courier name") }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tracking,
                    onValueChange = { tracking = it },
                    label = { Text("Tracking number") },
                    singleLine = true,
                    isError = showErrors && trackingMissing,
                    supportingText = if (showErrors && trackingMissing) ({ Text("Enter the tracking number") }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(300) },
                label = { Text("Note (optional)") },
                supportingText = { Text("Saved in the status history") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@Composable
private fun TrackingDialog(
    order: Order,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (courier: String?, tracking: String?) -> Unit,
) {
    var courier by rememberSaveable { mutableStateOf(order.courier.orEmpty()) }
    var tracking by rememberSaveable { mutableStateOf(order.trackingNumber.orEmpty()) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val empty = courier.isBlank() && tracking.isBlank()

    AdminConfirmDialog(
        title = "Tracking details",
        message = "Shown to the customer on their order.",
        confirmLabel = "Save",
        destructive = false,
        busy = busy,
        error = error ?: if (showErrors && empty) "Enter a courier or tracking number" else null,
        onDismiss = onDismiss,
        onConfirm = {
            showErrors = true
            if (empty) return@AdminConfirmDialog
            onConfirm(courier.trim().ifBlank { null }, tracking.trim().ifBlank { null })
        },
        extraContent = {
            OutlinedTextField(
                value = courier,
                onValueChange = { courier = it },
                label = { Text("Courier") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = tracking,
                onValueChange = { tracking = it },
                label = { Text("Tracking number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

@Composable
private fun CancelDialog(
    order: Order,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (refundChoice: String?) -> Unit,
) {
    val paid = order.needsRefundChoice
    var choice by rememberSaveable { mutableStateOf("money_refund") }
    AdminConfirmDialog(
        title = "Cancel order #${order.shortId}?",
        message = if (paid) {
            "The customer paid ${rupees(order.totalPrice)}. Choose how to refund them. Stock is returned automatically."
        } else {
            "No payment was collected, so no refund is needed. Stock is returned automatically."
        },
        confirmLabel = "Cancel order",
        busy = busy,
        error = error,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(if (paid) choice else null) },
        extraContent = if (paid) {
            {
                Column(Modifier.selectableGroup()) {
                    RefundOption(
                        selected = choice == "money_refund",
                        title = "Money refund",
                        subtitle = "${rupees(order.totalPrice)} back to the original payment method",
                        onClick = { choice = "money_refund" }
                    )
                    RefundOption(
                        selected = choice == "leaf_points",
                        title = "Leaf Points",
                        subtitle = "${order.leafPointsRefundAmount} points credited instantly",
                        onClick = { choice = "leaf_points" }
                    )
                }
            }
        } else null
    )
}

@Composable
private fun RefundOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ManualRefundDialog(
    order: Order,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (reference: String?) -> Unit,
) {
    var reference by rememberSaveable { mutableStateOf("") }
    val amount = order.refundAmount.takeIf { it > 0 } ?: order.totalPrice
    AdminConfirmDialog(
        title = "Mark refund as completed?",
        message = "Only do this after you have sent ${rupees(amount)} to the customer yourself " +
            "(bank transfer, UPI or the Razorpay dashboard). The customer will be told the refund is complete.",
        confirmLabel = "Mark refunded",
        destructive = false,
        busy = busy,
        error = error,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(reference.trim().ifBlank { null }) },
        extraContent = {
            OutlinedTextField(
                value = reference,
                onValueChange = { reference = it.take(100) },
                label = { Text("Reference (optional)") },
                placeholder = { Text("UTR / bank reference") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

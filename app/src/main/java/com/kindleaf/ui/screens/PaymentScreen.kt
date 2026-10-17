package com.kindleaf.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kindleaf.data.StoreSettingsStore
import com.kindleaf.models.Order
import com.kindleaf.notifications.OrderNotificationHelper
import com.kindleaf.ui.checkout.PaymentPhase
import com.kindleaf.ui.checkout.PaymentViewModel
import com.kindleaf.ui.theme.PrimaryGreen

/**
 * Pays for a `pending_payment` order through Razorpay Checkout.
 *
 * Flow: create Razorpay order (Edge Function) → Razorpay Checkout → verify signature (Edge Function)
 * → order becomes `placed`. If verification is interrupted, the Razorpay webhook confirms it and this
 * screen picks that up by polling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentScreen(
    orderId: String,
    onPaid: (orderId: String) -> Unit,
    onBack: () -> Unit,
    onOrderCancelled: () -> Unit,
) {
    val viewModel: PaymentViewModel = viewModel(
        key = "payment-$orderId",
        factory = PaymentViewModel.Factory(orderId)
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val order = state.order
    var confirmCancel by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshSilently() }

    LaunchedEffect(state.phase) {
        if (state.phase == PaymentPhase.Paid && order != null) {
            OrderNotificationHelper.showOrderPlaced(context, order.id, paidOnline = order.isPaid)
            onPaid(order.id)
        }
    }

    val working = state.phase in setOf(PaymentPhase.Preparing, PaymentPhase.InCheckout, PaymentPhase.Verifying)
    // Back stays usable while Razorpay's own screen is up, so a lost callback can never trap the user.
    val backEnabled = state.phase != PaymentPhase.Preparing && state.phase != PaymentPhase.Verifying

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = backEnabled) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.phase == PaymentPhase.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.phase == PaymentPhase.LoadFailed || order == null -> CenteredMessage(
                modifier = Modifier.padding(padding),
                title = "Couldn't load this order",
                body = state.message ?: "Please try again.",
                actionLabel = "Retry",
                onAction = viewModel::load,
            )

            state.phase == PaymentPhase.Closed -> CenteredMessage(
                modifier = Modifier.padding(padding),
                title = "Payment window closed",
                body = state.message ?: "This order is no longer awaiting payment.",
                actionLabel = "Back to shopping",
                onAction = onOrderCancelled,
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AmountCard(order = order, secondsLeft = state.secondsLeft)

                state.message?.let { message ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (state.isError) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.isError) {
                                Icon(
                                    Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.isError) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                when (state.phase) {
                    PaymentPhase.Verifying, PaymentPhase.Confirming -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (state.phase == PaymentPhase.Verifying) "Verifying your payment…"
                                else "Waiting for payment confirmation…",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (state.phase == PaymentPhase.Confirming) {
                            OutlinedButton(
                                onClick = onBack,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Check later in My Orders") }
                        }
                    }

                    else -> {
                        Button(
                            onClick = { context.findActivity()?.let(viewModel::pay) },
                            enabled = !working && !state.busy && !state.paymentsUnavailable,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen)
                        ) {
                            if (working) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Outlined.Lock, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (state.message != null && !state.paymentsUnavailable) "Try again · ₹${order.totalPrice}"
                                    else "Pay ₹${order.totalPrice} securely",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                        Text(
                            "UPI · Credit & debit cards · Netbanking · Wallets\n" +
                                "Payments are processed by Razorpay. Kindleaf never sees your card details or UPI PIN.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                        if (StoreSettingsStore.settings.codEnabled) {
                            OutlinedButton(
                                onClick = viewModel::switchToCod,
                                enabled = !working && !state.busy,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Pay ₹${order.totalPrice} on delivery instead") }
                        }
                        TextButton(
                            onClick = { confirmCancel = true },
                            enabled = !working && !state.busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Cancel this order", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel order?") },
            text = { Text("Nothing has been charged for this order. The items will be released back to stock.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    viewModel.cancelOrder(onOrderCancelled)
                }) { Text("Cancel order", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text("Keep order") }
            }
        )
    }
}

@Composable
private fun AmountCard(order: Order, secondsLeft: Long?) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Amount to pay",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "₹${order.totalPrice}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Order #${order.shortId} · ${order.itemCount} item${if (order.itemCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium
            )
            order.lineItems.take(3).forEach { line ->
                Text(
                    "• ${line.name} × ${line.quantity}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (order.lineItems.size > 3) {
                Text(
                    "• and ${order.lineItems.size - 3} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Deliver to ${order.deliveryAddress.name}, ${order.deliveryAddress.singleLine}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (secondsLeft != null && order.isAwaitingPayment) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Timer,
                        contentDescription = null,
                        tint = if (secondsLeft < 300) MaterialTheme.colorScheme.error else PrimaryGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (secondsLeft > 0) "Complete payment within %d:%02d".format(secondsLeft / 60, secondsLeft % 60)
                        else "Payment window has ended",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (secondsLeft < 300) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    modifier: Modifier,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onAction,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen)
        ) { Text(actionLabel) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

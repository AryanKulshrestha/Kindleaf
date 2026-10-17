package com.kindleaf.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.remote.OrdersRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Order
import com.kindleaf.ui.orders.OrderItemThumb
import com.kindleaf.ui.orders.SectionCard
import kotlinx.coroutines.launch

@Composable
fun OrderConfirmationScreen(
    orderId: String,
    onTrackOrder: (String) -> Unit,
    onContinueShopping: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var order by remember(orderId) { mutableStateOf(AppStateManager.orders.find { it.id == orderId }) }
    var loading by remember(orderId) { mutableStateOf(true) }
    var error by remember(orderId) { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        try {
            val fetched = OrdersRepository.fetchById(orderId)
            if (fetched != null) {
                order = fetched
                AppStateManager.upsertOrder(fetched)
                error = null
            } else if (order == null) {
                error = "We couldn't find this order. You can check it in My Orders."
            }
        } catch (e: Exception) {
            if (order == null) error = e.userMessage("Couldn't load your order details.")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(orderId) { load() }

    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    val current = order
    val awaitingPayment = current?.isAwaitingPayment == true

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val accent = if (awaitingPayment) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .scale(scale.value)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (awaitingPayment) Icons.Outlined.HourglassTop else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = accent
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                if (awaitingPayment) "Payment pending" else "Order placed!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (awaitingPayment) "Your order is reserved. Complete the payment from the order page to confirm it."
                else "Thank you for shopping with Kindleaf.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            when {
                current != null -> OrderSummaryCard(current)
                loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                else -> SectionCard {
                    Text(
                        "Order #${orderId.take(8).uppercase()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        error ?: "Couldn't load your order details.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { scope.launch { load() } }) { Text("Retry") }
                }
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = { onTrackOrder(orderId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    if (awaitingPayment) Icons.Outlined.Payment else Icons.Outlined.LocalShipping,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (awaitingPayment) "View order & pay" else "Track order",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onContinueShopping,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Outlined.ShoppingBag, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Continue shopping", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun OrderSummaryCard(order: Order) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Order number",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("#${order.shortId}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("₹${order.totalPrice}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Spacer(Modifier.height(12.dp))

        val items = order.lineItems
        if (items.isEmpty()) {
            Text(order.product.name, style = MaterialTheme.typography.bodyMedium)
        } else {
            val shown = items.take(3)
            shown.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    OrderItemThumb(item.imageUrl, contentDescription = null, size = 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "× ${item.quantity}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (items.size > shown.size) {
                Text(
                    "+ ${items.size - shown.size} more item(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Spacer(Modifier.height(12.dp))

        SummaryLine(
            icon = Icons.Outlined.Payments,
            label = "Payment",
            value = when {
                order.isPaid -> "Paid · ${order.paymentMethod}"
                order.isCod -> "Pay ₹${order.totalPrice} on delivery"
                else -> order.paymentStatusLabel
            }
        )
        if (order.estimatedDelivery.isNotBlank() && !order.isAwaitingPayment) {
            SummaryLine(icon = Icons.Outlined.LocalShipping, label = "Estimated delivery", value = order.estimatedDelivery)
        }
        if (order.deliveryAddress.singleLine.isNotBlank()) {
            SummaryLine(
                icon = Icons.Outlined.Place,
                label = "Delivering to",
                value = listOf(order.deliveryAddress.name, order.deliveryAddress.singleLine)
                    .filter { it.isNotBlank() }.joinToString(", ")
            )
        }
        if (order.pointsDiscount > 0) {
            SummaryLine(
                icon = Icons.Outlined.Eco,
                label = "Leaf Points used",
                value = "${order.pointsRedeemed} pts (−₹${order.pointsDiscount})"
            )
        }
    }
}

@Composable
private fun SummaryLine(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

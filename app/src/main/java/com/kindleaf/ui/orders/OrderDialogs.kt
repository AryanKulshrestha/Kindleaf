package com.kindleaf.ui.orders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kindleaf.data.remote.ReturnsRepository
import com.kindleaf.models.Order
import com.kindleaf.ui.theme.PrimaryGreen

const val REFUND_LEAF_POINTS = "leaf_points"
const val REFUND_MONEY = "money_refund"
const val REFUND_NONE = "none"

/** Confirmation for a customer cancel; prepaid orders must pick where the refund goes. */
@Composable
fun CancelOrderDialog(
    order: Order,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (refundChoice: String) -> Unit,
) {
    var choice by rememberSaveable { mutableStateOf(REFUND_LEAF_POINTS) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Cancel order #${order.shortId}?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (order.needsRefundChoice) {
                    Text("You paid ₹${order.totalPrice}. How would you like your refund?")
                    Column(Modifier.selectableGroup()) {
                        RefundOption(
                            selected = choice == REFUND_LEAF_POINTS,
                            title = "Leaf Points · ${order.leafPointsRefundAmount} pts",
                            subtitle = "Credited instantly to your Leaf Points balance",
                            enabled = !busy,
                            onClick = { choice = REFUND_LEAF_POINTS },
                        )
                        RefundOption(
                            selected = choice == REFUND_MONEY,
                            title = "Original payment method · ₹${order.totalPrice}",
                            subtitle = "Refunded in 5–7 working days",
                            enabled = !busy,
                            onClick = { choice = REFUND_MONEY },
                        )
                    }
                } else {
                    Text(
                        when {
                            order.isAwaitingPayment -> "This order hasn't been paid yet, so there is nothing to refund."
                            order.isCod -> "This is a Cash on Delivery order, so no payment has been taken."
                            else -> "This order will be cancelled."
                        }
                    )
                }
                if (order.pointsRedeemed > 0) {
                    Text(
                        "The ${order.pointsRedeemed} Leaf Points you used on this order will be returned to your balance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(if (order.needsRefundChoice) choice else REFUND_NONE) },
                enabled = !busy
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Cancel order", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Keep order") }
        }
    )
}

/** Return request form: reason, details and where the refund should go. */
@Composable
fun ReturnRequestDialog(
    order: Order,
    returnWindowDays: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (reason: String, details: String, refundChoice: String) -> Unit,
    error: String? = null,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf("") }
    var choice by rememberSaveable { mutableStateOf(REFUND_LEAF_POINTS) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val maxDetails = 500

    val reasonError = attempted && reason.isBlank()
    val detailsError = attempted && reason == "Other" && details.isBlank()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Request a return") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Returns are accepted within $returnWindowDays days of delivery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Reason", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Column(Modifier.selectableGroup()) {
                    ReturnsRepository.reasons.forEach { r ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .selectable(
                                    selected = reason == r,
                                    enabled = !busy,
                                    role = Role.RadioButton,
                                    onClick = { reason = r }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = reason == r, onClick = null, enabled = !busy)
                            Spacer(Modifier.width(8.dp))
                            Text(r, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (reasonError) {
                    Text("Choose a reason", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(maxDetails) },
                    label = { Text(if (reason == "Other") "Details (required)" else "Details (optional)") },
                    isError = detailsError,
                    supportingText = {
                        Text(if (detailsError) "Tell us what went wrong" else "${details.length}/$maxDetails")
                    },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )
                if (order.totalPrice > 0) {
                    Text("Refund to", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.selectableGroup()) {
                        RefundOption(
                            selected = choice == REFUND_LEAF_POINTS,
                            title = "Leaf Points · ${order.leafPointsRefundAmount} pts",
                            subtitle = "Credited as soon as the return is approved",
                            enabled = !busy,
                            onClick = { choice = REFUND_LEAF_POINTS },
                        )
                        RefundOption(
                            selected = choice == REFUND_MONEY,
                            title = if (order.isCod) "Bank transfer / UPI · ₹${order.totalPrice}"
                            else "Original payment method · ₹${order.totalPrice}",
                            subtitle = if (order.isCod) {
                                "This was a Cash on Delivery order, so our support team will contact you to arrange a bank transfer or UPI refund after approval."
                            } else {
                                "Refunded in 5–7 working days after approval"
                            },
                            enabled = !busy,
                            onClick = { choice = REFUND_MONEY },
                        )
                    }
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    attempted = true
                    if (reason.isNotBlank() && !(reason == "Other" && details.isBlank())) {
                        onSubmit(reason, details.trim(), if (order.totalPrice > 0) choice else REFUND_NONE)
                    }
                },
                enabled = !busy
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Submit request", color = PrimaryGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        }
    )
}

@Composable
private fun RefundOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

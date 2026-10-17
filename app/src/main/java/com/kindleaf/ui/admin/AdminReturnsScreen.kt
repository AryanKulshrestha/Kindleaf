package com.kindleaf.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AssignmentReturn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kindleaf.data.remote.ReturnsRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.ReturnRequest
import kotlinx.coroutines.launch

private const val RETURNS_ALL = "all"

private fun returnTone(status: String): AdminTone = when (status) {
    "requested" -> AdminTone.Warning
    "approved" -> AdminTone.Success
    "rejected" -> AdminTone.Error
    else -> AdminTone.Neutral
}

private fun String.shortOrderId() = take(8).uppercase()

private data class ReturnDecision(val request: ReturnRequest, val approve: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminReturnsScreen(onBack: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("requested") }
    var returns by remember { mutableStateOf<List<ReturnRequest>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var decision by remember { mutableStateOf<ReturnDecision?>(null) }
    var refundReminder by remember { mutableStateOf<ReturnRequest?>(null) }

    LaunchedEffect(filter, reloadKey) {
        if (!refreshing) loading = true
        error = null
        attempt { ReturnsRepository.fetchAll(filter.takeIf { it != RETURNS_ALL }) }
            .onSuccess { returns = it }
            .onFailure {
                returns = emptyList()
                error = it.userMessage("Couldn't load return requests.")
            }
        loading = false
        refreshing = false
    }

    Scaffold(
        topBar = { AdminTopBar(title = "Returns", onBack = onBack) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AdminFilterChips(
                options = listOf(
                    "requested" to "Requested",
                    "approved" to "Approved",
                    "rejected" to "Rejected",
                    RETURNS_ALL to "All",
                ),
                selected = filter,
                onSelect = { filter = it }
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
                        error != null -> item {
                            AdminErrorState(error.orEmpty(), onRetry = { reloadKey++ }, modifier = Modifier.fillParentMaxSize())
                        }
                        returns.isEmpty() -> item {
                            AdminEmptyState(
                                if (filter == "requested") "No returns waiting for a decision" else "No return requests here",
                                icon = Icons.AutoMirrored.Outlined.AssignmentReturn,
                                modifier = Modifier.fillParentMaxSize()
                            )
                        }
                        else -> items(returns, key = { it.id }) { request ->
                            ReturnCard(
                                request = request,
                                onApprove = { decision = ReturnDecision(request, approve = true) },
                                onReject = { decision = ReturnDecision(request, approve = false) },
                            )
                        }
                    }
                }
            }
        }
    }

    decision?.let { d ->
        ResolveReturnDialog(
            decision = d,
            onDismiss = { decision = null },
            onResolved = { resolved ->
                decision = null
                returns = if (filter == RETURNS_ALL || filter == resolved.status) {
                    returns.map { if (it.id == resolved.id) resolved else it }
                } else {
                    returns.filterNot { it.id == resolved.id }
                }
                if (resolved.status == "approved" && resolved.refundChoice == "money_refund") {
                    refundReminder = resolved
                }
            }
        )
    }

    refundReminder?.let { r ->
        AlertDialog(
            onDismissRequest = { refundReminder = null },
            title = { Text("Return approved") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The customer asked for a money refund. The order is now marked Returned with the refund pending.")
                    SelectionContainer {
                        Text(
                            "Open order #${r.orderId.shortOrderId()} from the Orders tab (the Refunds filter lists it) " +
                                "to refund via Razorpay or mark it refunded manually.",
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { refundReminder = null }) { Text("Got it") } }
        )
    }
}

@Composable
private fun ReturnCard(
    request: ReturnRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text("Order #${request.orderId.shortOrderId()}", fontWeight = FontWeight.SemiBold)
                }
                AdminBadge(request.statusLabel.removePrefix("Return ").replaceFirstChar { it.uppercase() }, returnTone(request.status))
            }
            Text(request.createdAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            AdminKeyValue("Reason", request.reason.ifBlank { "—" })
            AdminKeyValue("Refund as", refundChoiceLabel(request.refundChoice))
            if (request.details.isNotBlank()) {
                Text(
                    request.details,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (request.status != "requested") {
                request.resolvedAt?.let { AdminKeyValue("Resolved", it) }
                if (request.adminNote.isNotBlank()) AdminKeyValue("Note to customer", request.adminNote)
            } else {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                        Text("Reject", color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text("Approve") }
                }
            }
        }
    }
}

@Composable
private fun ResolveReturnDialog(
    decision: ReturnDecision,
    onDismiss: () -> Unit,
    onResolved: (ReturnRequest) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val request = decision.request
    var note by rememberSaveable(request.id, decision.approve) { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val noteRequired = !decision.approve
    val noteError = if (noteRequired && note.isBlank()) "Tell the customer why" else null

    val message = if (decision.approve) {
        "Order #${request.orderId.shortOrderId()} will be marked Returned. " + when (request.refundChoice) {
            "leaf_points" -> "Leaf Points for the full order value are credited to the customer immediately."
            "money_refund" -> "A money refund will be set to pending — you then process it from the order screen."
            else -> "No refund was requested."
        }
    } else {
        "The customer will be told their return for order #${request.orderId.shortOrderId()} was not approved."
    }

    AdminConfirmDialog(
        title = if (decision.approve) "Approve return?" else "Reject return?",
        message = message,
        confirmLabel = if (decision.approve) "Approve" else "Reject",
        destructive = !decision.approve,
        busy = busy,
        error = error,
        onDismiss = { if (!busy) onDismiss() },
        onConfirm = {
            showErrors = true
            if (noteError != null) return@AdminConfirmDialog
            scope.launch {
                busy = true
                error = null
                attempt { ReturnsRepository.resolve(request.id, decision.approve, note.trim()) }
                    .onSuccess {
                        busy = false
                        onResolved(it)
                    }
                    .onFailure {
                        error = it.userMessage("Couldn't update the return.")
                        busy = false
                    }
            }
        },
        extraContent = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(300) },
                label = { Text(if (noteRequired) "Reason for rejection *" else "Note to customer (optional)") },
                isError = showErrors && noteError != null,
                supportingText = {
                    Text(
                        when {
                            showErrors && noteError != null -> noteError
                            decision.approve -> "Saved on the return request"
                            else -> "Included in the customer's notification"
                        }
                    )
                },
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

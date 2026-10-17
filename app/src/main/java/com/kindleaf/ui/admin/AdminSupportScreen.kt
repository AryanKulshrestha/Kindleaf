package com.kindleaf.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kindleaf.data.remote.SupportRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.SupportTicket
import kotlinx.coroutines.launch

private const val TICKETS_ALL = "all"
private val TICKET_STATUSES = listOf("open", "in_progress", "resolved")

private fun ticketStatusLabel(status: String): String = when (status) {
    "open" -> "Open"
    "in_progress" -> "In progress"
    "resolved" -> "Resolved"
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun ticketTone(status: String): AdminTone = when (status) {
    "open" -> AdminTone.Warning
    "in_progress" -> AdminTone.Info
    "resolved" -> AdminTone.Success
    else -> AdminTone.Neutral
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSupportScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf("open") }
    var tickets by remember { mutableStateOf<List<SupportTicket>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var openTicketId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(filter, reloadKey) {
        if (!refreshing) loading = true
        error = null
        attempt { SupportRepository.fetchAll(filter.takeIf { it != TICKETS_ALL }) }
            .onSuccess { tickets = it }
            .onFailure {
                tickets = emptyList()
                error = it.userMessage("Couldn't load support tickets.")
            }
        loading = false
        refreshing = false
    }

    Scaffold(
        topBar = { AdminTopBar(title = "Support tickets", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AdminFilterChips(
                options = TICKET_STATUSES.map { it to ticketStatusLabel(it) } + (TICKETS_ALL to "All"),
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
                        tickets.isEmpty() -> item {
                            AdminEmptyState(
                                if (filter == "open") "No open tickets. Nice work!" else "No tickets here",
                                icon = Icons.Outlined.SupportAgent,
                                modifier = Modifier.fillParentMaxSize()
                            )
                        }
                        else -> items(tickets, key = { it.id }) { ticket ->
                            TicketRow(ticket = ticket, onClick = { openTicketId = ticket.id })
                        }
                    }
                }
            }
        }
    }

    openTicketId?.let { id -> tickets.firstOrNull { it.id == id } }?.let { ticket ->
        run {
            TicketDialog(
                ticket = ticket,
                onDismiss = { openTicketId = null },
                onSaved = { updated ->
                    openTicketId = null
                    tickets = if (filter == TICKETS_ALL || filter == updated.status) {
                        tickets.map { if (it.id == updated.id) updated else it }
                    } else {
                        tickets.filterNot { it.id == updated.id }
                    }
                    scope.launch { snackbar.showSnackbar("Ticket updated") }
                }
            )
        }
    }
}

@Composable
private fun TicketRow(ticket: SupportTicket, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open ticket", onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ticket.subject.ifBlank { "(No subject)" },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                AdminBadge(ticketStatusLabel(ticket.status), ticketTone(ticket.status))
            }
            Text(
                ticket.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(
                    ticket.email.takeIf { it.isNotBlank() },
                    ticket.orderId?.let { "Order #${it.take(8).uppercase()}" },
                    ticket.createdAt.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (ticket.adminNote.isNotBlank()) {
                Text(
                    "Reply: ${ticket.adminNote}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TicketDialog(
    ticket: SupportTicket,
    onDismiss: () -> Unit,
    onSaved: (SupportTicket) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by rememberSaveable(ticket.id) { mutableStateOf(ticket.status) }
    var note by rememberSaveable(ticket.id) { mutableStateOf(ticket.adminNote) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val changed = status != ticket.status || note.trim() != ticket.adminNote

    AdminConfirmDialog(
        title = ticket.subject.ifBlank { "Support ticket" },
        message = ticket.message,
        confirmLabel = "Save",
        destructive = false,
        busy = saving,
        error = error,
        onDismiss = { if (!saving) onDismiss() },
        onConfirm = {
            if (!changed) {
                onDismiss()
                return@AdminConfirmDialog
            }
            scope.launch {
                saving = true
                error = null
                attempt { SupportRepository.update(ticket.id, status, note.trim()) }
                    .onSuccess {
                        saving = false
                        onSaved(it)
                    }
                    .onFailure {
                        error = it.userMessage("Couldn't update the ticket.")
                        saving = false
                    }
            }
        },
        extraContent = {
            HorizontalDivider()
            SelectionContainer {
                Column {
                    AdminKeyValue("Email", ticket.email.ifBlank { "—" })
                    AdminKeyValue("Order", ticket.orderId?.take(8)?.uppercase()?.let { "#$it" } ?: "—")
                    AdminKeyValue("Created", ticket.createdAt.ifBlank { "—" })
                }
            }
            Text("Status", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TICKET_STATUSES.forEachIndexed { index, s ->
                    SegmentedButton(
                        selected = status == s,
                        onClick = { status = s },
                        shape = SegmentedButtonDefaults.itemShape(index, TICKET_STATUSES.size),
                        label = { Text(ticketStatusLabel(s), maxLines = 1) }
                    )
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(1000) },
                label = { Text("Reply to customer") },
                supportingText = { Text("Shown in the customer's \"My tickets\"") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}

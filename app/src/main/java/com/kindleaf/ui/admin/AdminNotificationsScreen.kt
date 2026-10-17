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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kindleaf.data.remote.NotificationInsertDto
import com.kindleaf.data.remote.NotificationsRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.AppNotification
import com.kindleaf.models.NotificationType
import kotlinx.coroutines.launch

private const val TITLE_MAX = 80
private const val MESSAGE_MAX = 500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminNotificationsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var broadcasts by remember { mutableStateOf<List<AppNotification>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var composing by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AppNotification?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        if (!refreshing) loading = true
        error = null
        attempt { NotificationsRepository.fetchBroadcasts() }
            .onSuccess { broadcasts = it }
            .onFailure { error = it.userMessage("Couldn't load broadcasts.") }
        loading = false
        refreshing = false
    }

    Scaffold(
        topBar = { AdminTopBar(title = "Broadcasts", subtitle = "Messages shown to every customer", onBack = onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { composing = true },
                icon = { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null) },
                text = { Text("New broadcast") }
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    loading -> item { AdminLoading(Modifier.fillParentMaxSize()) }
                    error != null && broadcasts.isEmpty() -> item {
                        AdminErrorState(error.orEmpty(), onRetry = { reloadKey++ }, modifier = Modifier.fillParentMaxSize())
                    }
                    broadcasts.isEmpty() -> item {
                        AdminEmptyState(
                            "No broadcasts sent yet. Order and points alerts are sent automatically.",
                            icon = Icons.Outlined.Campaign,
                            modifier = Modifier.fillParentMaxSize()
                        )
                    }
                    else -> {
                        error?.let { msg -> item { AdminInlineError(msg) } }
                        items(broadcasts, key = { it.id }) { notification ->
                            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            AdminBadge(
                                                if (notification.type == NotificationType.PROMO) "Promo" else "General",
                                                if (notification.type == NotificationType.PROMO) AdminTone.Warning else AdminTone.Neutral
                                            )
                                            Text(
                                                "  ${notification.timestamp}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(notification.title, fontWeight = FontWeight.SemiBold)
                                        Text(notification.message, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    IconButton(onClick = { deleteError = null; deleting = notification }) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "Delete broadcast")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (composing) {
        ComposeBroadcastDialog(
            onDismiss = { composing = false },
            onSent = { sent ->
                composing = false
                broadcasts = listOf(sent) + broadcasts.filterNot { it.id == sent.id }
                scope.launch { snackbar.showSnackbar("Broadcast sent to all customers") }
            }
        )
    }

    deleting?.let { notification ->
        AdminConfirmDialog(
            title = "Delete broadcast?",
            message = "\"${notification.title}\" will disappear from every customer's notifications.",
            confirmLabel = "Delete",
            busy = deleteBusy,
            error = deleteError,
            onDismiss = { if (!deleteBusy) deleting = null },
            onConfirm = {
                scope.launch {
                    deleteBusy = true
                    deleteError = null
                    attempt { NotificationsRepository.delete(notification.id) }
                        .onSuccess {
                            broadcasts = broadcasts.filterNot { it.id == notification.id }
                            deleting = null
                        }
                        .onFailure { deleteError = it.userMessage("Couldn't delete the broadcast.") }
                    deleteBusy = false
                }
            }
        )
    }
}

@Composable
private fun ComposeBroadcastDialog(
    onDismiss: () -> Unit,
    onSent: (AppNotification) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var promo by rememberSaveable { mutableStateOf(true) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val titleError = if (title.isBlank()) "Title is required" else null
    val messageError = when {
        message.isBlank() -> "Message is required"
        message.trim().length < 5 -> "Message is too short"
        else -> null
    }

    if (!confirming) {
        AdminConfirmDialog(
            title = "New broadcast",
            message = "Sent as an in-app notification to every customer.",
            confirmLabel = "Review",
            destructive = false,
            onDismiss = onDismiss,
            onConfirm = {
                showErrors = true
                if (titleError == null && messageError == null) confirming = true
            },
            extraContent = {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = promo,
                        onClick = { promo = true },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        label = { Text("Promo") }
                    )
                    SegmentedButton(
                        selected = !promo,
                        onClick = { promo = false },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        label = { Text("General") }
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(TITLE_MAX) },
                    label = { Text("Title *") },
                    isError = showErrors && titleError != null,
                    supportingText = {
                        Text(if (showErrors && titleError != null) titleError else "${title.length}/$TITLE_MAX")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(MESSAGE_MAX) },
                    label = { Text("Message *") },
                    isError = showErrors && messageError != null,
                    supportingText = {
                        Text(if (showErrors && messageError != null) messageError else "${message.length}/$MESSAGE_MAX")
                    },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    } else {
        AdminConfirmDialog(
            title = "Send to all customers?",
            message = "This will be shown to all customers and can't be edited after sending.\n\n" +
                "${title.trim()}\n${message.trim()}",
            confirmLabel = "Send",
            destructive = false,
            busy = sending,
            error = error,
            onDismiss = { if (!sending) confirming = false },
            onConfirm = {
                scope.launch {
                    sending = true
                    error = null
                    attempt {
                        NotificationsRepository.create(
                            NotificationInsertDto(
                                userId = null,
                                title = title.trim(),
                                message = message.trim(),
                                type = if (promo) NotificationType.PROMO.name else NotificationType.GENERAL.name,
                            )
                        )
                    }.onSuccess {
                        sending = false
                        onSent(it)
                    }.onFailure {
                        error = it.userMessage("Couldn't send the broadcast.")
                        sending = false
                    }
                }
            }
        )
    }
}

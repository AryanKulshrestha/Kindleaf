package com.kindleaf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.remote.NotificationsRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.AppNotification
import com.kindleaf.models.NotificationType
import com.kindleaf.ui.orders.CenteredMessage
import com.kindleaf.ui.orders.currentUserIdOrNull
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onOpenOrder: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notifications = AppStateManager.notifications

    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Swiped-away notifications waiting for the Undo window to pass before they are deleted.
    val pendingDeletes = remember { mutableStateMapOf<String, AppNotification>() }

    suspend fun load() {
        val userId = currentUserIdOrNull()
        if (userId == null) {
            error = "Please sign in to see your notifications."
            return
        }
        try {
            AppStateManager.replaceNotifications(NotificationsRepository.fetchForUser(userId))
            error = null
        } catch (e: Exception) {
            val message = e.userMessage("Couldn't load notifications.")
            error = message
            if (notifications.isNotEmpty()) scope.launch { snackbar.showSnackbar(message) }
        }
    }

    LaunchedEffect(Unit) {
        load()
        initialLoading = false
    }

    // If the screen closes during the Undo window, finish the deletes anyway.
    DisposableEffect(Unit) {
        onDispose {
            if (pendingDeletes.isNotEmpty()) {
                val leftovers = pendingDeletes.values.toList()
                pendingDeletes.clear()
                val flushScope = MainScope()
                flushScope.launch {
                    leftovers.forEach { AppStateManager.deleteNotification(it) }
                    flushScope.cancel()
                }
            }
        }
    }

    fun delete(notification: AppNotification) {
        pendingDeletes[notification.id] = notification
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = "Notification deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            val pending = pendingDeletes.remove(notification.id) ?: return@launch
            if (result != SnackbarResult.ActionPerformed) {
                AppStateManager.deleteNotification(pending)
            }
        }
    }

    val visible = notifications.filter { it.id !in pendingDeletes }
    val hasUnread = visible.any { !it.isRead }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Notifications",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (hasUnread) {
                        TextButton(onClick = {
                            scope.launch { AppStateManager.syncMarkAllNotificationsRead() }
                        }) {
                            Text("Mark all read", color = MaterialTheme.colorScheme.primary)
                        }
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
                initialLoading && notifications.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                visible.isEmpty() && error != null -> CenteredMessage(
                    icon = Icons.Outlined.CloudOff,
                    title = "Couldn't load notifications",
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

                visible.isEmpty() -> CenteredMessage(
                    icon = Icons.Outlined.NotificationsNone,
                    title = "No notifications yet",
                    message = "We'll let you know about your orders and offers here.",
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visible, key = { it.id }) { notification ->
                        val open = {
                            scope.launch { AppStateManager.syncMarkNotificationRead(notification.id) }
                            notification.orderId?.let(onOpenOrder)
                            Unit
                        }
                        if (notification.isBroadcast) {
                            NotificationCard(notification = notification, onClick = open, onDelete = null)
                        } else {
                            SwipeToDelete(
                                onDelete = { delete(notification) },
                                modifier = Modifier.animateItem()
                            ) {
                                NotificationCard(
                                    notification = notification,
                                    onClick = open,
                                    onDelete = { delete(notification) }
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentOnDelete by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                currentOnDelete()
                true
            } else {
                false
            }
        }
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        content()
    }
}

@Composable
private fun NotificationCard(
    notification: AppNotification,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val containerColor = if (notification.isRead) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val accent = when (notification.type) {
        NotificationType.ORDER -> MaterialTheme.colorScheme.primary
        NotificationType.PROMO -> MaterialTheme.colorScheme.tertiary
        NotificationType.GENERAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    if (!notification.isRead) append("Unread. ")
                    append("${notification.title}. ${notification.message}. ${notification.timestamp}")
                    if (notification.orderId != null) append(". Opens the order")
                }
                if (onDelete != null) {
                    customActions = listOf(
                        CustomAccessibilityAction("Delete notification") { onDelete(); true }
                    )
                }
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (notification.isRead) 1.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (notification.type) {
                        NotificationType.ORDER -> Icons.Outlined.LocalShipping
                        NotificationType.PROMO -> Icons.Outlined.LocalOffer
                        NotificationType.GENERAL -> Icons.Outlined.Info
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = accent
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = notification.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (notification.isRead) FontWeight.Medium else FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!notification.isRead) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = notification.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = notification.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (notification.orderId != null) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            "View order",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

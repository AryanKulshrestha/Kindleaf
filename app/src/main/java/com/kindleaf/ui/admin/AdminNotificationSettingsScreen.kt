package com.kindleaf.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kindleaf.data.NotificationSettingsStore
import com.kindleaf.data.remote.NotificationSettingsRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.NotificationEventSetting
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminNotificationSettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkBusy by remember { mutableStateOf(false) }
    var confirmAllOff by remember { mutableStateOf(false) }
    val settings = NotificationSettingsStore.settings

    LaunchedEffect(reloadKey) {
        if (!refreshing) loading = true
        error = null
        NotificationSettingsStore.refresh()
        if (NotificationSettingsStore.settings.isEmpty()) {
            // The store hides failures; ask the server directly so we can explain what went wrong.
            attempt { NotificationSettingsRepository.fetchAll() }
                .onFailure { error = it.userMessage("Couldn't load notification settings.") }
        }
        loading = false
        refreshing = false
    }

    fun setAll(enabled: Boolean) {
        scope.launch {
            bulkBusy = true
            error = null
            attempt { NotificationSettingsStore.setAllEnabled(enabled) }
                .onFailure { error = it.userMessage("Couldn't update all settings.") }
            NotificationSettingsStore.refresh()
            bulkBusy = false
        }
    }

    Scaffold(
        topBar = {
            AdminTopBar(
                title = "Notification controls",
                onBack = onBack,
                actions = {
                    if (settings.isNotEmpty()) {
                        TextButton(onClick = { setAll(true) }, enabled = !bulkBusy) { Text("All on") }
                        TextButton(onClick = { confirmAllOff = true }, enabled = !bulkBusy) { Text("All off") }
                    }
                }
            )
        }
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    loading && settings.isEmpty() -> item { AdminLoading(Modifier.fillParentMaxSize()) }
                    error != null && settings.isEmpty() -> item {
                        AdminErrorState(error.orEmpty(), onRetry = { reloadKey++ }, modifier = Modifier.fillParentMaxSize())
                    }
                    settings.isEmpty() -> item {
                        AdminEmptyState(
                            "No notification events are configured on the server.",
                            icon = Icons.Outlined.NotificationsOff,
                            modifier = Modifier.fillParentMaxSize()
                        )
                    }
                    else -> {
                        item {
                            Text(
                                "Choose which automatic customer alerts are sent (in-app and phone notifications). " +
                                    "Admin broadcasts are always delivered.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        error?.let { msg -> item { AdminInlineError(msg) } }
                        items(settings, key = { it.eventKey }) { setting ->
                            EventToggleRow(
                                setting = setting,
                                busy = bulkBusy || setting.eventKey in pending,
                                onToggle = { enabled ->
                                    pending = pending + setting.eventKey
                                    scope.launch {
                                        attempt { NotificationSettingsStore.setEnabled(setting.eventKey, enabled) }
                                            .onSuccess { error = null }
                                            .onFailure { e ->
                                                error = e.userMessage("Couldn't update ${setting.label}.")
                                                NotificationSettingsStore.refresh()
                                            }
                                        pending = pending - setting.eventKey
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmAllOff) {
        AdminConfirmDialog(
            title = "Turn off all alerts?",
            message = "Customers will stop receiving automatic order, payment and points notifications until you turn them back on.",
            confirmLabel = "Turn all off",
            onDismiss = { confirmAllOff = false },
            onConfirm = {
                confirmAllOff = false
                setAll(false)
            }
        )
    }
}

@Composable
private fun EventToggleRow(
    setting: NotificationEventSetting,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(setting.label, fontWeight = FontWeight.SemiBold)
                if (setting.description.isNotBlank()) {
                    Text(
                        setting.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Switch(
                    checked = setting.isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = setting.label }
                )
            }
        }
    }
}

package com.kindleaf.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kindleaf.data.remote.NotificationSettingsRepository
import com.kindleaf.models.NotificationEventSetting

/**
 * Cached admin toggles for which automated notifications fire.
 * In-app notifications are created by database triggers that read the same toggles;
 * this cache only gates local tray notifications and powers the admin screen.
 * Missing / unloaded keys default to enabled.
 */
object NotificationSettingsStore {

    var settings by mutableStateOf<List<NotificationEventSetting>>(emptyList())
        private set

    private var loaded = false

    fun isEnabled(eventKey: String): Boolean {
        if (!loaded) return true
        val row = settings.find { it.eventKey == eventKey } ?: return true
        return row.isEnabled
    }

    suspend fun refresh() {
        runCatching { NotificationSettingsRepository.fetchAll() }
            .onSuccess {
                settings = it
                loaded = true
            }
    }

    suspend fun setEnabled(eventKey: String, enabled: Boolean) {
        val updated = NotificationSettingsRepository.setEnabled(eventKey, enabled)
        settings = settings.map { if (it.eventKey == eventKey) updated else it }
            .ifEmpty { listOf(updated) }
            .sortedBy { it.sortOrder }
        loaded = true
    }

    suspend fun setAllEnabled(enabled: Boolean) {
        val keys = settings.map { it.eventKey }.ifEmpty {
            NotificationSettingsRepository.fetchAll().map { it.eventKey }
        }
        keys.forEach { key ->
            runCatching { NotificationSettingsRepository.setEnabled(key, enabled) }
        }
        settings = settings.map { it.copy(isEnabled = enabled) }
        if (settings.isEmpty()) refresh()
        loaded = true
    }
}

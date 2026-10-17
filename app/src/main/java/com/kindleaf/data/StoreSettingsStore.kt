package com.kindleaf.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kindleaf.data.remote.StoreSettingsRepository
import com.kindleaf.models.StoreSettings

/** Cached `store_settings` row: support contacts, legal links, points and payment rules. */
object StoreSettingsStore {

    var settings by mutableStateOf(StoreSettings())
        private set

    suspend fun refresh() {
        runCatching { StoreSettingsRepository.fetch() }.onSuccess { settings = it }
    }

    suspend fun save(updated: StoreSettings): StoreSettings {
        val saved = StoreSettingsRepository.update(updated)
        settings = saved
        return saved
    }
}

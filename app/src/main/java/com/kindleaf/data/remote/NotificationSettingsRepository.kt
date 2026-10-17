package com.kindleaf.data.remote

import com.kindleaf.data.SupabaseHelper
import com.kindleaf.models.NotificationEventSetting
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class NotificationEventSettingDto(
    @SerialName("event_key") val eventKey: String,
    val label: String,
    val description: String = "",
    @SerialName("is_enabled") val isEnabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
) {
    fun toModel() = NotificationEventSetting(
        eventKey = eventKey,
        label = label,
        description = description,
        isEnabled = isEnabled,
        sortOrder = sortOrder,
    )
}

@Serializable
data class NotificationEventEnabledUpdateDto(
    @SerialName("is_enabled") val isEnabled: Boolean,
    @SerialName("updated_at") val updatedAt: String? = null,
)

object NotificationSettingsRepository {

    suspend fun fetchAll(): List<NotificationEventSetting> {
        return SupabaseHelper.client
            .from("notification_event_settings")
            .select {
                order("sort_order", Order.ASCENDING)
            }
            .decodeList<NotificationEventSettingDto>()
            .map { it.toModel() }
    }

    suspend fun setEnabled(eventKey: String, isEnabled: Boolean): NotificationEventSetting {
        return SupabaseHelper.client.from("notification_event_settings").update(
            NotificationEventEnabledUpdateDto(
                isEnabled = isEnabled,
                updatedAt = Instant.now().toString(),
            )
        ) {
            filter { eq("event_key", eventKey) }
            select()
        }.decodeSingle<NotificationEventSettingDto>().toModel()
    }
}

package com.kindleaf.data.remote

import com.kindleaf.data.SupabaseHelper
import com.kindleaf.models.AppNotification
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order

object NotificationsRepository {

    suspend fun fetchForUser(userId: String, limit: Int = 100): List<AppNotification> {
        // RLS returns the user's rows plus broadcast rows (user_id is null).
        return SupabaseHelper.client
            .from("notifications")
            .select {
                filter { or { eq("user_id", userId); exact("user_id", null) } }
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }
            .decodeList<NotificationDto>()
            .filter { it.userId == null || it.userId == userId }
            .map { it.toAppNotification() }
    }

    /** Admin: broadcasts only (per-customer notifications are private). */
    suspend fun fetchBroadcasts(limit: Int = 100): List<AppNotification> {
        return SupabaseHelper.client
            .from("notifications")
            .select {
                filter { exact("user_id", null) }
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }
            .decodeList<NotificationDto>()
            .map { it.toAppNotification() }
    }

    suspend fun create(dto: NotificationInsertDto): AppNotification {
        return SupabaseHelper.client.from("notifications").insert(dto) {
            select()
        }.decodeSingle<NotificationDto>().toAppNotification()
    }

    suspend fun markRead(id: String) {
        SupabaseHelper.client.from("notifications").update(
            NotificationReadUpdateDto(isRead = true)
        ) {
            filter { eq("id", id) }
        }
    }

    suspend fun markAllRead(userId: String) {
        SupabaseHelper.client.from("notifications").update(
            NotificationReadUpdateDto(isRead = true)
        ) {
            filter { eq("user_id", userId) }
        }
    }

    suspend fun delete(id: String) {
        SupabaseHelper.client.from("notifications").delete {
            filter { eq("id", id) }
        }
    }
}

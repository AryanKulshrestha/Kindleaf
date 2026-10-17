package com.kindleaf.data.remote

import com.kindleaf.data.SupabaseHelper
import com.kindleaf.models.AdminStats
import com.kindleaf.models.PointsTransaction
import com.kindleaf.models.ReturnRequest
import com.kindleaf.models.StoreSettings
import com.kindleaf.models.SupportTicket
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** Server-synced cart (table `cart_items`). */
object CartRepository {

    suspend fun fetch(userId: String): List<CartRowDto> =
        SupabaseHelper.client.from("cart_items")
            .select { filter { eq("user_id", userId) } }
            .decodeList()

    suspend fun setQuantity(userId: String, itemId: String, quantity: Int) {
        if (quantity <= 0) {
            remove(userId, itemId)
            return
        }
        SupabaseHelper.client.from("cart_items").upsert(
            CartRowDto(userId = userId, itemId = itemId, quantity = quantity)
        ) {
            onConflict = "user_id,item_id"
        }
    }

    suspend fun remove(userId: String, itemId: String) {
        SupabaseHelper.client.from("cart_items").delete {
            filter {
                eq("user_id", userId)
                eq("item_id", itemId)
            }
        }
    }

    suspend fun clear(userId: String) {
        SupabaseHelper.client.from("cart_items").delete {
            filter { eq("user_id", userId) }
        }
    }
}

/** Server-synced wishlist (table `wishlist_items`). */
object WishlistRepository {

    suspend fun fetch(userId: String): List<String> =
        SupabaseHelper.client.from("wishlist_items")
            .select { filter { eq("user_id", userId) } }
            .decodeList<WishlistRowDto>()
            .map { it.itemId }

    suspend fun add(userId: String, itemId: String) {
        SupabaseHelper.client.from("wishlist_items").upsert(
            WishlistRowDto(userId = userId, itemId = itemId)
        ) {
            onConflict = "user_id,item_id"
            ignoreDuplicates = true
        }
    }

    suspend fun remove(userId: String, itemId: String) {
        SupabaseHelper.client.from("wishlist_items").delete {
            filter {
                eq("user_id", userId)
                eq("item_id", itemId)
            }
        }
    }
}

/** Leaf Points history. Balances change only through server functions. */
object PointsRepository {

    suspend fun fetchLedger(userId: String, limit: Int = 100): List<PointsTransaction> =
        SupabaseHelper.client.from("points_ledger")
            .select {
                filter { eq("user_id", userId) }
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }
            .decodeList<PointsLedgerDto>()
            .map { it.toModel() }

    /** Admin: add (positive) or deduct (negative) points with a reason. Returns the new balance. */
    suspend fun adminAdjust(userId: String, delta: Int, note: String): Int =
        SupabaseHelper.client.postgrest.rpc(
            function = "admin_adjust_points",
            parameters = buildJsonObject {
                put("p_user_id", userId)
                put("p_delta", delta)
                put("p_note", note)
            }
        ).decodeAs<Int>()
}

object ReturnsRepository {

    val reasons = listOf(
        "Damaged or leaking packaging",
        "Wrong item delivered",
        "Item missing from the order",
        "Product expired or stale",
        "Quality not as expected",
        "Other",
    )

    suspend fun request(
        orderId: String,
        reason: String,
        details: String,
        refundChoice: String,
    ): ReturnRequest =
        SupabaseHelper.client.postgrest.rpc(
            function = "request_return",
            parameters = buildJsonObject {
                put("p_order_id", orderId)
                put("p_reason", reason)
                put("p_details", details)
                put("p_refund_choice", refundChoice)
            }
        ).decodeAs<ReturnRequestDto>().toModel()

    suspend fun fetchForOrder(orderId: String): List<ReturnRequest> =
        SupabaseHelper.client.from("return_requests")
            .select {
                filter { eq("order_id", orderId) }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<ReturnRequestDto>()
            .map { it.toModel() }

    /** Admin list; pass `requested` to see only those awaiting a decision. */
    suspend fun fetchAll(status: String? = null): List<ReturnRequest> =
        SupabaseHelper.client.from("return_requests")
            .select {
                if (!status.isNullOrBlank()) filter { eq("status", status) }
                order("created_at", Order.DESCENDING)
                limit(200)
            }
            .decodeList<ReturnRequestDto>()
            .map { it.toModel() }

    suspend fun resolve(returnId: String, approve: Boolean, note: String): ReturnRequest =
        SupabaseHelper.client.postgrest.rpc(
            function = "admin_resolve_return",
            parameters = buildJsonObject {
                put("p_return_id", returnId)
                put("p_approve", approve)
                put("p_note", note)
            }
        ).decodeAs<ReturnRequestDto>().toModel()
}

object SupportRepository {

    suspend fun create(
        userId: String,
        email: String,
        subject: String,
        message: String,
        orderId: String? = null,
    ): SupportTicket =
        SupabaseHelper.client.from("support_tickets").insert(
            SupportTicketInsertDto(
                userId = userId,
                orderId = orderId,
                email = email,
                subject = subject.trim(),
                message = message.trim(),
            )
        ) { select() }.decodeSingle<SupportTicketDto>().toModel()

    suspend fun fetchMine(userId: String): List<SupportTicket> =
        SupabaseHelper.client.from("support_tickets")
            .select {
                filter { eq("user_id", userId) }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<SupportTicketDto>()
            .map { it.toModel() }

    suspend fun fetchAll(status: String? = null): List<SupportTicket> =
        SupabaseHelper.client.from("support_tickets")
            .select {
                if (!status.isNullOrBlank()) filter { eq("status", status) }
                order("created_at", Order.DESCENDING)
                limit(200)
            }
            .decodeList<SupportTicketDto>()
            .map { it.toModel() }

    suspend fun update(id: String, status: String, adminNote: String): SupportTicket =
        SupabaseHelper.client.from("support_tickets").update(
            SupportTicketUpdateDto(status = status, adminNote = adminNote, updatedAt = Instant.now().toString())
        ) {
            filter { eq("id", id) }
            select()
        }.decodeSingle<SupportTicketDto>().toModel()
}

object StoreSettingsRepository {

    suspend fun fetch(): StoreSettings =
        SupabaseHelper.client.from("store_settings")
            .select { limit(1) }
            .decodeList<StoreSettingsDto>()
            .firstOrNull()
            ?.toModel()
            ?: StoreSettings()

    suspend fun update(settings: StoreSettings): StoreSettings =
        SupabaseHelper.client.from("store_settings").update(StoreSettingsDto.from(settings)) {
            filter { eq("id", true) }
            select()
        }.decodeSingle<StoreSettingsDto>().toModel()
}

object AdminRepository {

    suspend fun stats(days: Int = 30): AdminStats =
        SupabaseHelper.client.postgrest.rpc(
            function = "admin_stats",
            parameters = buildJsonObject { put("p_days", days) }
        ).decodeAs<AdminStatsDto>().toModel()
}

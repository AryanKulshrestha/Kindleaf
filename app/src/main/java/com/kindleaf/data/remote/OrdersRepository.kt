package com.kindleaf.data.remote

import com.kindleaf.data.CartItem
import com.kindleaf.data.SupabaseHelper
import com.kindleaf.models.CartQuote
import com.kindleaf.models.Order
import com.kindleaf.models.OrderStatusEvent
import com.kindleaf.models.PaymentMethod
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order as QueryOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Orders are created and changed only through server RPCs, which compute prices,
 * validate coupons/stock/points and enforce the status machine.
 */
object OrdersRepository {

    const val PAGE_SIZE = 20

    private fun linesJson(cartItems: List<CartItem>) =
        Json.encodeToJsonElement(cartItems.map { CartLineRequest(it.product.id, it.quantity) })

    /** Server-side price preview for the checkout screen. */
    suspend fun quote(
        cartItems: List<CartItem>,
        couponCode: String? = null,
        redeemPoints: Int = 0,
    ): CartQuote {
        return SupabaseHelper.client.postgrest.rpc(
            function = "quote_cart",
            parameters = buildJsonObject {
                put("p_items", linesJson(cartItems))
                put("p_coupon_code", couponCode?.trim()?.ifBlank { null })
                put("p_redeem_points", redeemPoints.coerceAtLeast(0))
            }
        ).decodeAs<QuoteDto>().toModel()
    }

    /**
     * Places an order. COD orders come back as `placed`; online orders come back as
     * `pending_payment` and must be paid through [PaymentsRepository].
     */
    suspend fun placeOrder(
        addressId: String,
        cartItems: List<CartItem>,
        paymentMethod: PaymentMethod,
        couponCode: String? = null,
        redeemPoints: Int = 0,
    ): Order {
        val created = SupabaseHelper.client.postgrest.rpc(
            function = "place_order",
            parameters = buildJsonObject {
                put("p_address_id", addressId)
                put("p_items", linesJson(cartItems))
                put("p_payment_method", paymentMethod.rpcValue)
                put("p_coupon_code", couponCode?.trim()?.ifBlank { null })
                put("p_redeem_points", redeemPoints.coerceAtLeast(0))
            }
        ).decodeAs<OrderDto>()
        return attachLineItems(listOf(created)).first()
    }

    suspend fun fetchForUser(userId: String, limit: Int = 100, offset: Int = 0): List<Order> {
        val orders = SupabaseHelper.client
            .from("orders")
            .select {
                filter { eq("user_id", userId) }
                order("created_at", QueryOrder.DESCENDING)
                range(offset.toLong(), (offset + limit - 1).toLong())
            }
            .decodeList<OrderDto>()
        return attachLineItems(orders)
    }

    /** Admin list with optional status filter and paging. */
    suspend fun fetchAll(
        status: String? = null,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): List<Order> {
        val orders = SupabaseHelper.client
            .from("orders")
            .select {
                if (!status.isNullOrBlank()) filter { eq("status", status) }
                order("created_at", QueryOrder.DESCENDING)
                range(offset.toLong(), (offset + limit - 1).toLong())
            }
            .decodeList<OrderDto>()
        return attachLineItems(orders)
    }

    /** Orders with a refund that still needs action (pending / processing / failed). */
    suspend fun fetchWithPendingRefunds(): List<Order> {
        val orders = SupabaseHelper.client
            .from("orders")
            .select {
                filter { isIn("refund_status", listOf("pending", "processing", "failed")) }
                order("updated_at", QueryOrder.DESCENDING)
            }
            .decodeList<OrderDto>()
        return attachLineItems(orders)
    }

    suspend fun fetchById(orderId: String): Order? {
        val order = SupabaseHelper.client
            .from("orders")
            .select {
                filter { eq("id", orderId) }
                limit(1)
            }
            .decodeList<OrderDto>()
            .firstOrNull() ?: return null
        return attachLineItems(listOf(order)).firstOrNull()
    }

    suspend fun fetchStatusEvents(orderId: String): List<OrderStatusEvent> {
        return SupabaseHelper.client
            .from("order_status_events")
            .select {
                filter { eq("order_id", orderId) }
                order("created_at", QueryOrder.ASCENDING)
            }
            .decodeList<OrderStatusEventDto>()
            .map { it.toModel() }
    }

    /**
     * Admin: advance status and/or set tracking details. Transitions are validated on the server;
     * customer notifications are sent by a database trigger.
     */
    suspend fun adminUpdateStatus(
        orderId: String,
        status: String? = null,
        trackingNumber: String? = null,
        courier: String? = null,
        note: String? = null,
    ): Order = rpcOrder("admin_update_order_status") {
        put("p_order_id", orderId)
        put("p_status", status)
        put("p_tracking_number", trackingNumber)
        put("p_courier", courier)
        put("p_note", note)
    }

    /** Customer cancel. [refundChoice] is `leaf_points` or `money_refund` for paid orders, else `none`. */
    suspend fun cancelOrder(orderId: String, refundChoice: String): Order = rpcOrder("cancel_own_order") {
        put("p_order_id", orderId)
        put("p_refund_choice", refundChoice)
    }

    /** Admin cancel: unpaid/COD orders need no refund; paid ones default to a money refund. */
    suspend fun adminCancelOrder(orderId: String, refundChoice: String? = null): Order =
        rpcOrder("admin_cancel_order") {
            put("p_order_id", orderId)
            put("p_refund_choice", refundChoice)
        }

    /** Admin: mark a refund done by hand (e.g. bank transfer for a COD return). */
    suspend fun completeRefundManually(orderId: String, reference: String? = null): Order =
        rpcOrder("admin_complete_refund") {
            put("p_order_id", orderId)
            put("p_reference", reference?.trim()?.ifBlank { null })
        }

    /** Customer: pay on delivery instead of completing an online payment. */
    suspend fun switchToCod(orderId: String): Order = rpcOrder("switch_order_to_cod") {
        put("p_order_id", orderId)
    }

    private suspend fun rpcOrder(
        function: String,
        params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): Order {
        val updated = SupabaseHelper.client.postgrest.rpc(
            function = function,
            parameters = buildJsonObject(params),
        ).decodeAs<OrderDto>()
        return attachLineItems(listOf(updated)).first()
    }

    internal suspend fun attachLineItems(orders: List<OrderDto>): List<Order> {
        if (orders.isEmpty()) return emptyList()
        val ids = orders.map { it.id }
        val allItems = SupabaseHelper.client
            .from("order_items")
            .select {
                filter { isIn("order_id", ids) }
            }
            .decodeList<OrderItemDto>()
            .groupBy { it.orderId }
        return orders.map { dto ->
            dto.toOrder(allItems[dto.id].orEmpty().map { it.toLineItem() })
        }
    }
}

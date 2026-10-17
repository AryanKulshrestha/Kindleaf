package com.kindleaf.data.remote

import com.kindleaf.models.AdminStats
import com.kindleaf.models.CartQuote
import com.kindleaf.models.DailyStat
import com.kindleaf.models.LowStockItem
import com.kindleaf.models.OrderStatusEvent
import com.kindleaf.models.PointsTransaction
import com.kindleaf.models.QuoteCharge
import com.kindleaf.models.QuoteLine
import com.kindleaf.models.RazorpayCheckoutInfo
import com.kindleaf.models.ReturnRequest
import com.kindleaf.models.StoreSettings
import com.kindleaf.models.SupportTicket
import com.kindleaf.models.TopProduct
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Cart quote (quote_cart RPC) ──

@Serializable
data class QuoteDto(
    val lines: List<QuoteLineDto> = emptyList(),
    val errors: List<String> = emptyList(),
    val subtotal: Int = 0,
    @SerialName("delivery_fee") val deliveryFee: Int = 0,
    @SerialName("extra_charges") val extraCharges: Int = 0,
    val charges: List<QuoteChargeDto> = emptyList(),
    @SerialName("coupon_code") val couponCode: String? = null,
    @SerialName("coupon_discount") val couponDiscount: Int = 0,
    @SerialName("coupon_error") val couponError: String? = null,
    @SerialName("points_balance") val pointsBalance: Int = 0,
    @SerialName("points_max") val pointsMax: Int = 0,
    @SerialName("points_applied") val pointsApplied: Int = 0,
    @SerialName("points_discount") val pointsDiscount: Int = 0,
    val total: Int = 0,
    @SerialName("cod_enabled") val codEnabled: Boolean = true,
    @SerialName("online_enabled") val onlineEnabled: Boolean = true,
) {
    fun toModel() = CartQuote(
        lines = lines.map { it.toModel() },
        errors = errors,
        subtotal = subtotal,
        deliveryFee = deliveryFee,
        extraCharges = extraCharges,
        charges = charges.map { it.toModel() },
        couponCode = couponCode,
        couponDiscount = couponDiscount,
        couponError = couponError,
        pointsBalance = pointsBalance,
        pointsMax = pointsMax,
        pointsApplied = pointsApplied,
        pointsDiscount = pointsDiscount,
        total = total,
        codEnabled = codEnabled,
        onlineEnabled = onlineEnabled,
    )
}

@Serializable
data class QuoteLineDto(
    @SerialName("item_id") val itemId: String,
    val name: String = "",
    val price: Int = 0,
    val quantity: Int = 0,
    @SerialName("line_total") val lineTotal: Int = 0,
    @SerialName("stock_qty") val stockQty: Int = 0,
) {
    fun toModel() = QuoteLine(itemId, name, price, quantity, lineTotal, stockQty)
}

@Serializable
data class QuoteChargeDto(
    val key: String,
    val name: String = "",
    val amount: Int = 0,
    @SerialName("free_above") val freeAbove: Int? = null,
) {
    fun toModel() = QuoteCharge(key, name, amount, freeAbove)
}

@Serializable
data class CartLineRequest(
    @SerialName("item_id") val itemId: String,
    val quantity: Int,
)

// ── Cart / wishlist rows ──

@Serializable
data class CartRowDto(
    @SerialName("user_id") val userId: String,
    @SerialName("item_id") val itemId: String,
    val quantity: Int,
)

@Serializable
data class WishlistRowDto(
    @SerialName("user_id") val userId: String,
    @SerialName("item_id") val itemId: String,
)

// ── Points ledger ──

@Serializable
data class PointsLedgerDto(
    val id: String,
    val delta: Int,
    @SerialName("balance_after") val balanceAfter: Int,
    val reason: String,
    val note: String = "",
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toModel() = PointsTransaction(
        id = id,
        delta = delta,
        balanceAfter = balanceAfter,
        reason = reason,
        note = note,
        orderId = orderId,
        createdAt = formatDateTime(createdAt),
    )
}

// ── Returns ──

@Serializable
data class ReturnRequestDto(
    val id: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("user_id") val userId: String? = null,
    val reason: String = "",
    val details: String = "",
    @SerialName("refund_choice") val refundChoice: String = "money_refund",
    val status: String = "requested",
    @SerialName("admin_note") val adminNote: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("resolved_at") val resolvedAt: String? = null,
) {
    fun toModel() = ReturnRequest(
        id = id,
        orderId = orderId,
        userId = userId,
        reason = reason,
        details = details,
        refundChoice = refundChoice,
        status = status,
        adminNote = adminNote,
        createdAt = formatDateTime(createdAt),
        resolvedAt = resolvedAt?.let(::formatDateTime),
    )
}

// ── Support ──

@Serializable
data class SupportTicketDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("order_id") val orderId: String? = null,
    val email: String = "",
    val subject: String = "",
    val message: String = "",
    val status: String = "open",
    @SerialName("admin_note") val adminNote: String = "",
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toModel() = SupportTicket(
        id = id,
        userId = userId,
        orderId = orderId,
        email = email,
        subject = subject,
        message = message,
        status = status,
        adminNote = adminNote,
        createdAt = formatDateTime(createdAt),
    )
}

@Serializable
data class SupportTicketInsertDto(
    @SerialName("user_id") val userId: String,
    @SerialName("order_id") val orderId: String? = null,
    val email: String,
    val subject: String,
    val message: String,
)

@Serializable
data class SupportTicketUpdateDto(
    val status: String,
    @SerialName("admin_note") val adminNote: String,
    @SerialName("updated_at") val updatedAt: String,
)

// ── Store settings ──

@Serializable
data class StoreSettingsDto(
    @SerialName("points_earn_percent") val pointsEarnPercent: Int = 5,
    @SerialName("points_redeem_max_percent") val pointsRedeemMaxPercent: Int = 20,
    @SerialName("return_window_days") val returnWindowDays: Int = 7,
    @SerialName("unpaid_order_expiry_minutes") val unpaidOrderExpiryMinutes: Int = 30,
    @SerialName("max_quantity_per_item") val maxQuantityPerItem: Int = 20,
    @SerialName("cod_enabled") val codEnabled: Boolean = true,
    @SerialName("online_payments_enabled") val onlinePaymentsEnabled: Boolean = true,
    @SerialName("support_email") val supportEmail: String = "",
    @SerialName("support_phone") val supportPhone: String = "",
    @SerialName("support_whatsapp") val supportWhatsapp: String = "",
    @SerialName("privacy_policy_url") val privacyPolicyUrl: String = "",
    @SerialName("terms_url") val termsUrl: String = "",
) {
    fun toModel() = StoreSettings(
        pointsEarnPercent = pointsEarnPercent,
        pointsRedeemMaxPercent = pointsRedeemMaxPercent,
        returnWindowDays = returnWindowDays,
        unpaidOrderExpiryMinutes = unpaidOrderExpiryMinutes,
        maxQuantityPerItem = maxQuantityPerItem,
        codEnabled = codEnabled,
        onlinePaymentsEnabled = onlinePaymentsEnabled,
        supportEmail = supportEmail,
        supportPhone = supportPhone,
        supportWhatsapp = supportWhatsapp,
        privacyPolicyUrl = privacyPolicyUrl,
        termsUrl = termsUrl,
    )

    companion object {
        fun from(s: StoreSettings) = StoreSettingsDto(
            pointsEarnPercent = s.pointsEarnPercent,
            pointsRedeemMaxPercent = s.pointsRedeemMaxPercent,
            returnWindowDays = s.returnWindowDays,
            unpaidOrderExpiryMinutes = s.unpaidOrderExpiryMinutes,
            maxQuantityPerItem = s.maxQuantityPerItem,
            codEnabled = s.codEnabled,
            onlinePaymentsEnabled = s.onlinePaymentsEnabled,
            supportEmail = s.supportEmail.trim(),
            supportPhone = s.supportPhone.trim(),
            supportWhatsapp = s.supportWhatsapp.trim(),
            privacyPolicyUrl = s.privacyPolicyUrl.trim(),
            termsUrl = s.termsUrl.trim(),
        )
    }
}

// ── Order events ──

@Serializable
data class OrderStatusEventDto(
    val id: String,
    val status: String,
    val note: String = "",
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toModel() = OrderStatusEvent(id, status, note, formatDateTime(createdAt))
}

// ── Review votes ──

@Serializable
data class ReviewVoteDto(
    @SerialName("review_id") val reviewId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("is_helpful") val isHelpful: Boolean,
)

// ── Admin stats ──

@Serializable
data class AdminStatsDto(
    val days: Int = 30,
    val revenue: Int = 0,
    val orders: Int = 0,
    @SerialName("avg_order_value") val avgOrderValue: Int = 0,
    @SerialName("to_fulfil") val toFulfil: Int = 0,
    @SerialName("in_transit") val inTransit: Int = 0,
    @SerialName("awaiting_payment") val awaitingPayment: Int = 0,
    @SerialName("refunds_pending") val refundsPending: Int = 0,
    @SerialName("returns_pending") val returnsPending: Int = 0,
    @SerialName("open_tickets") val openTickets: Int = 0,
    val customers: Int = 0,
    @SerialName("new_customers") val newCustomers: Int = 0,
    val products: Int = 0,
    @SerialName("active_products") val activeProducts: Int = 0,
    val categories: Int = 0,
    @SerialName("low_stock") val lowStock: List<LowStockDto> = emptyList(),
    @SerialName("top_products") val topProducts: List<TopProductDto> = emptyList(),
    val daily: List<DailyStatDto> = emptyList(),
    @SerialName("status_counts") val statusCounts: Map<String, Int> = emptyMap(),
) {
    fun toModel() = AdminStats(
        days = days,
        revenue = revenue,
        orders = orders,
        avgOrderValue = avgOrderValue,
        toFulfil = toFulfil,
        inTransit = inTransit,
        awaitingPayment = awaitingPayment,
        refundsPending = refundsPending,
        returnsPending = returnsPending,
        openTickets = openTickets,
        customers = customers,
        newCustomers = newCustomers,
        products = products,
        activeProducts = activeProducts,
        categories = categories,
        lowStock = lowStock.map { LowStockItem(it.id, it.name, it.stockQty) },
        topProducts = topProducts.map { TopProduct(it.name, it.quantity, it.revenue) },
        daily = daily.map { DailyStat(it.day, it.orders, it.revenue) },
        statusCounts = statusCounts,
    )
}

@Serializable
data class LowStockDto(val id: String, val name: String, @SerialName("stock_qty") val stockQty: Int)

@Serializable
data class TopProductDto(val name: String, val quantity: Int = 0, val revenue: Int = 0)

@Serializable
data class DailyStatDto(val day: String, val orders: Int = 0, val revenue: Int = 0)

// ── Razorpay Edge Functions ──

@Serializable
data class RazorpayCheckoutDto(
    @SerialName("key_id") val keyId: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("razorpay_order_id") val razorpayOrderId: String,
    val amount: Long,
    val currency: String = "INR",
    val name: String = "Kindleaf",
    val description: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    val prefill: RazorpayPrefillDto = RazorpayPrefillDto(),
) {
    fun toModel() = RazorpayCheckoutInfo(
        keyId = keyId,
        orderId = orderId,
        razorpayOrderId = razorpayOrderId,
        amountPaise = amount,
        currency = currency,
        name = name,
        description = description,
        expiresAt = expiresAt,
        prefillName = prefill.name,
        prefillEmail = prefill.email,
        prefillContact = prefill.contact,
    )
}

@Serializable
data class RazorpayPrefillDto(
    val name: String = "",
    val email: String = "",
    val contact: String = "",
)

@Serializable
data class EdgeOrderResponse(val order: OrderDto)

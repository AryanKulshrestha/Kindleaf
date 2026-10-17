package com.kindleaf.models

/** Server-computed price breakdown from the `quote_cart` RPC. The server is the source of truth. */
data class CartQuote(
    val lines: List<QuoteLine> = emptyList(),
    val errors: List<String> = emptyList(),
    val subtotal: Int = 0,
    val deliveryFee: Int = 0,
    val extraCharges: Int = 0,
    val charges: List<QuoteCharge> = emptyList(),
    val couponCode: String? = null,
    val couponDiscount: Int = 0,
    val couponError: String? = null,
    val pointsBalance: Int = 0,
    val pointsMax: Int = 0,
    val pointsApplied: Int = 0,
    val pointsDiscount: Int = 0,
    val total: Int = 0,
    val codEnabled: Boolean = true,
    val onlineEnabled: Boolean = true,
)

data class QuoteLine(
    val itemId: String,
    val name: String,
    val price: Int,
    val quantity: Int,
    val lineTotal: Int,
    val stockQty: Int,
)

data class QuoteCharge(
    val key: String,
    val name: String,
    val amount: Int,
    val freeAbove: Int? = null,
)

enum class PaymentMethod(val rpcValue: String, val label: String) {
    COD("cod", "Cash on Delivery"),
    ONLINE("online", "Pay Online (UPI, Cards, Netbanking, Wallets)"),
}

data class PointsTransaction(
    val id: String,
    val delta: Int,
    val balanceAfter: Int,
    val reason: String,
    val note: String,
    val orderId: String?,
    val createdAt: String,
) {
    val reasonLabel: String
        get() = when (reason) {
            "earned" -> "Earned"
            "redeemed" -> "Redeemed"
            "refund" -> "Refund"
            "cancel_return" -> "Returned (cancelled order)"
            "return_reversal" -> "Reversed (returned order)"
            "admin_adjustment" -> "Adjustment"
            "migration" -> "Opening balance"
            else -> reason.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
}

data class ReturnRequest(
    val id: String,
    val orderId: String,
    val userId: String?,
    val reason: String,
    val details: String,
    val refundChoice: String,
    val status: String,
    val adminNote: String,
    val createdAt: String,
    val resolvedAt: String?,
) {
    val statusLabel: String
        get() = when (status) {
            "requested" -> "Return requested"
            "approved" -> "Return approved"
            "rejected" -> "Return rejected"
            else -> status
        }
}

data class SupportTicket(
    val id: String,
    val userId: String?,
    val orderId: String?,
    val email: String,
    val subject: String,
    val message: String,
    val status: String,
    val adminNote: String,
    val createdAt: String,
)

data class StoreSettings(
    val pointsEarnPercent: Int = 5,
    val pointsRedeemMaxPercent: Int = 20,
    val returnWindowDays: Int = 7,
    val unpaidOrderExpiryMinutes: Int = 30,
    val maxQuantityPerItem: Int = 20,
    val codEnabled: Boolean = true,
    val onlinePaymentsEnabled: Boolean = true,
    val supportEmail: String = "",
    val supportPhone: String = "",
    val supportWhatsapp: String = "",
    val privacyPolicyUrl: String = "",
    val termsUrl: String = "",
)

data class OrderStatusEvent(
    val id: String,
    val status: String,
    val note: String,
    val createdAt: String,
)

data class AdminStats(
    val days: Int = 30,
    val revenue: Int = 0,
    val orders: Int = 0,
    val avgOrderValue: Int = 0,
    val toFulfil: Int = 0,
    val inTransit: Int = 0,
    val awaitingPayment: Int = 0,
    val refundsPending: Int = 0,
    val returnsPending: Int = 0,
    val openTickets: Int = 0,
    val customers: Int = 0,
    val newCustomers: Int = 0,
    val products: Int = 0,
    val activeProducts: Int = 0,
    val categories: Int = 0,
    val lowStock: List<LowStockItem> = emptyList(),
    val topProducts: List<TopProduct> = emptyList(),
    val daily: List<DailyStat> = emptyList(),
    val statusCounts: Map<String, Int> = emptyMap(),
)

data class LowStockItem(val id: String, val name: String, val stockQty: Int)
data class TopProduct(val name: String, val quantity: Int, val revenue: Int)
data class DailyStat(val day: String, val orders: Int, val revenue: Int)

/** What the Android Razorpay Checkout needs; returned by the razorpay-create-order Edge Function. */
data class RazorpayCheckoutInfo(
    val keyId: String,
    val orderId: String,
    val razorpayOrderId: String,
    val amountPaise: Long,
    val currency: String,
    val name: String,
    val description: String,
    val expiresAt: String?,
    val prefillName: String,
    val prefillEmail: String,
    val prefillContact: String,
)

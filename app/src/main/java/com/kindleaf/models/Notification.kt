package com.kindleaf.models

data class AppNotification(
    val id: String,
    val title: String,
    val message: String,
    val timestamp: String,
    val type: NotificationType,
    val isRead: Boolean = false,
    /** Set for order-related notifications; tapping opens that order. */
    val orderId: String? = null,
    /** Broadcasts (no owner) cannot be deleted by customers. */
    val isBroadcast: Boolean = false,
)

enum class NotificationType {
    ORDER,
    PROMO,
    GENERAL
}

package com.kindleaf.models

object NotificationEventKeys {
    const val ORDER_PLACED = "order_placed"
    const val ORDER_CANCELLED = "order_cancelled"
    const val ORDER_STATUS_UPDATED = "order_status_updated"
    const val LEAF_POINTS_ADDED = "leaf_points_added"
    const val LEAF_POINTS_DEDUCTED = "leaf_points_deducted"
    const val REFUND_COMPLETED = "refund_completed"
    const val PAYMENT_RECEIVED = "payment_received"
    const val PAYMENT_FAILED = "payment_failed"
    const val RETURN_UPDATED = "return_updated"
}

data class NotificationEventSetting(
    val eventKey: String,
    val label: String,
    val description: String,
    val isEnabled: Boolean,
    val sortOrder: Int = 0,
)

package com.kindleaf.models

data class Order(
    val id: String,
    /** First line item as a product, for compact list rows. Use [lineItems] for the full order. */
    val product: Product,
    val quantity: Int,
    val totalPrice: Int,
    val placedDate: String,
    val statuses: List<OrderStatus>,
    val deliveryAddress: DeliveryAddress,
    val estimatedDelivery: String,
    val status: String = "placed",
    val subtotal: Int = 0,
    val deliveryFee: Int = 0,
    val extraCharges: Int = 0,
    val discount: Int = 0,
    val pointsRedeemed: Int = 0,
    val pointsDiscount: Int = 0,
    val pointsEarned: Int = 0,
    val paymentMethod: String = "",
    val paymentStatus: String = "pending",
    val couponCode: String? = null,
    val lineItems: List<OrderLineItem> = emptyList(),
    val userId: String = "",
    val cancelRefundChoice: String = "none",
    val refundStatus: String = "none",
    val refundAmount: Int = 0,
    val cancelledAt: String? = null,
    val deliveredAt: String? = null,
    val paidAt: String? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
    val trackingNumber: String? = null,
    val courier: String? = null,
    val razorpayOrderId: String? = null,
    val razorpayPaymentId: String? = null,
) {
    val shortId: String get() = id.take(8).uppercase()

    val isCod: Boolean
        get() = paymentStatus == "cod_pending" || paymentMethod.contains("cash on delivery", ignoreCase = true)

    val isAwaitingPayment: Boolean get() = status == "pending_payment"

    val isPaid: Boolean get() = paymentStatus == "paid"

    val canCancel: Boolean
        get() = status == "pending_payment" || status == "placed" || status == "confirmed"

    /** Prepaid orders need a refund choice when cancelled; COD / unpaid ones do not. */
    val needsRefundChoice: Boolean get() = isPaid && totalPrice > 0

    val leafPointsRefundAmount: Int
        get() = totalPrice.coerceAtLeast(0) * 10

    val itemCount: Int get() = lineItems.sumOf { it.quantity }.coerceAtLeast(quantity)

    val paymentStatusLabel: String
        get() = when (paymentStatus) {
            "paid" -> "Paid"
            "cod_pending" -> "Pay on delivery"
            "pending" -> "Awaiting payment"
            "failed" -> "Payment failed"
            "refunded" -> "Refunded"
            else -> paymentStatus
        }

    val refundStatusLabel: String?
        get() = when (refundStatus) {
            "pending" -> "Refund pending"
            "processing" -> "Refund processing"
            "completed" -> if (cancelRefundChoice == "leaf_points") "Refunded as Leaf Points" else "Refund completed"
            "failed" -> "Refund failed — we are retrying"
            else -> null
        }
}

data class OrderLineItem(
    val id: String = "",
    val itemId: String? = null,
    val name: String,
    val price: Int,
    val quantity: Int,
    val imageUrl: String = "",
    val size: String = "",
) {
    val lineTotal: Int get() = price * quantity
}

data class OrderStatus(
    val title: String,
    val subtitle: String,
    val isCompleted: Boolean,
    val isCurrent: Boolean = false
)

data class DeliveryAddress(
    val name: String,
    val addressLine: String,
    val city: String,
    val state: String,
    val pincode: String,
    val phone: String
) {
    val singleLine: String
        get() = listOf(addressLine, city, "$state - $pincode")
            .filter { it.isNotBlank() && it != " - " }
            .joinToString(", ")
}

object OrderStatusFlow {
    val steps = listOf(
        "placed" to "Order Placed",
        "confirmed" to "Order Confirmed",
        "packed" to "Packed",
        "shipped" to "Shipped",
        "out_for_delivery" to "Out for Delivery",
        "delivered" to "Delivered",
    )

    /** Every status an order can be in, for admin filters. */
    val allStatuses = listOf("pending_payment") + steps.map { it.first } + listOf("cancelled", "returned")

    fun timeline(
        status: String,
        estimatedDelivery: String,
        refundStatus: String = "none",
        cancelRefundChoice: String = "none",
    ): List<OrderStatus> {
        when (status) {
            "pending_payment" -> return listOf(
                OrderStatus("Awaiting payment", "Complete payment to confirm this order", isCompleted = false, isCurrent = true)
            )
            "cancelled" -> {
                val subtitle = when {
                    cancelRefundChoice == "leaf_points" -> "Cancelled · Leaf Points credited"
                    refundStatus == "pending" || refundStatus == "processing" -> "Cancelled · Refund in progress (5–7 working days)"
                    refundStatus == "completed" && cancelRefundChoice == "money_refund" -> "Cancelled · Refund completed"
                    else -> "Order was cancelled"
                }
                return listOf(OrderStatus("Cancelled", subtitle, isCompleted = true, isCurrent = true))
            }
            "returned" -> {
                val subtitle = when {
                    cancelRefundChoice == "leaf_points" -> "Returned · Leaf Points credited"
                    refundStatus == "completed" -> "Returned · Refund completed"
                    refundStatus == "pending" || refundStatus == "processing" -> "Returned · Refund in progress"
                    else -> "Order was returned"
                }
                return listOf(OrderStatus("Returned", subtitle, isCompleted = true, isCurrent = true))
            }
        }
        val currentIndex = steps.indexOfFirst { it.first == status }.coerceAtLeast(0)
        return steps.mapIndexed { index, (_, title) ->
            val subtitle = when {
                index < currentIndex -> "Completed"
                index == currentIndex -> "Current"
                title == "Delivered" && estimatedDelivery.isNotBlank() -> "Estimated $estimatedDelivery"
                else -> "Pending"
            }
            OrderStatus(
                title = title,
                subtitle = subtitle,
                isCompleted = index <= currentIndex,
                isCurrent = index == currentIndex
            )
        }
    }

    /** Next forward step an admin can move the order to (the server enforces the same rules). */
    fun nextStatus(current: String): String? {
        if (current == "pending_payment") return null
        val index = steps.indexOfFirst { it.first == current }
        if (index < 0 || index >= steps.lastIndex) return null
        return steps[index + 1].first
    }

    fun label(status: String): String = when (status) {
        "pending_payment" -> "Awaiting Payment"
        "cancelled" -> "Cancelled"
        "returned" -> "Returned"
        else -> steps.find { it.first == status }?.second
            ?: status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

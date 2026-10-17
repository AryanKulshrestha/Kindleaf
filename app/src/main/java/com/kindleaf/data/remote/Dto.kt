package com.kindleaf.data.remote

import com.kindleaf.models.Address
import com.kindleaf.models.AppNotification
import com.kindleaf.models.Category
import com.kindleaf.models.Charge
import com.kindleaf.models.Coupon
import com.kindleaf.models.CouponType
import com.kindleaf.models.DeliveryAddress
import com.kindleaf.models.NotificationType
import com.kindleaf.models.Order
import com.kindleaf.models.OrderLineItem
import com.kindleaf.models.OrderStatusFlow
import com.kindleaf.models.Product
import com.kindleaf.models.Review
import com.kindleaf.models.UserProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class ProfileDto(
    val id: String,
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    @SerialName("leaf_points") val leafPoints: Int = 0,
    @SerialName("avatar_initials") val avatarInitials: String = "U",
    val role: String = "customer",
) {
    fun toUserProfile() = UserProfile(
        id = id,
        name = name,
        phone = phone,
        email = email,
        leafPoints = leafPoints,
        avatarInitials = avatarInitials.ifBlank { "U" },
        role = role.ifBlank { "customer" },
    )
}

@Serializable
data class ProfileInsertDto(
    val id: String,
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    @SerialName("leaf_points") val leafPoints: Int = 0,
    @SerialName("avatar_initials") val avatarInitials: String = "U",
)

/** Email is owned by Supabase Auth and Leaf Points by the ledger, so neither is writable here. */
@Serializable
data class ProfileUpdateDto(
    val name: String,
    val phone: String,
    @SerialName("avatar_initials") val avatarInitials: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ItemDto(
    val id: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val rating: Float = 0f,
    @SerialName("review_count") val reviewCount: Int = 0,
    val size: String = "",
    val price: Int = 0,
    @SerialName("image_url") val imageUrl: String = "",
    val category: String = "All Teas",
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("stock_qty") val stockQty: Int = 0,
    @SerialName("low_stock_threshold") val lowStockThreshold: Int = 5,
    val mrp: Int? = null,
) {
    fun toProduct(isWishlisted: Boolean = false) = Product(
        id = id,
        name = name,
        description = description,
        tags = tags,
        rating = rating,
        reviewCount = reviewCount,
        size = size,
        price = price,
        imageUrl = imageUrl,
        isWishlisted = isWishlisted,
        category = category,
        categoryId = categoryId,
        stockQty = stockQty,
        lowStockThreshold = lowStockThreshold,
        mrp = mrp,
    )
}

/** Rating and review_count are computed from reviews on the server and are not writable. */
@Serializable
data class ItemWriteDto(
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val size: String = "",
    val price: Int = 0,
    val mrp: Int? = null,
    @SerialName("image_url") val imageUrl: String = "",
    val category: String = "All Teas",
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("stock_qty") val stockQty: Int = 0,
    @SerialName("low_stock_threshold") val lowStockThreshold: Int = 5,
)

@Serializable
data class ItemActiveUpdateDto(
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class ChargeDto(
    val id: String,
    val key: String,
    val name: String,
    val amount: Int = 0,
    @SerialName("is_enabled") val isEnabled: Boolean = true,
    @SerialName("free_above") val freeAbove: Int? = null,
    val description: String = "",
    @SerialName("sort_order") val sortOrder: Int = 0,
) {
    fun toCharge() = Charge(
        id = id,
        key = key,
        name = name,
        amount = amount,
        isEnabled = isEnabled,
        freeAbove = freeAbove,
        description = description,
        sortOrder = sortOrder,
    )
}

@Serializable
data class ChargeWriteDto(
    val key: String,
    val name: String,
    val amount: Int,
    @SerialName("is_enabled") val isEnabled: Boolean = true,
    @SerialName("free_above") val freeAbove: Int? = null,
    val description: String = "",
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ChargeEnabledUpdateDto(
    @SerialName("is_enabled") val isEnabled: Boolean,
)

@Serializable
data class AddressDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    val label: String = "Home",
    val name: String,
    @SerialName("address_line") val addressLine: String,
    val city: String,
    val state: String,
    val pincode: String,
    val phone: String,
    @SerialName("is_default") val isDefault: Boolean = false,
) {
    fun toAddress() = Address(
        id = id,
        label = label,
        name = name,
        addressLine = addressLine,
        city = city,
        state = state,
        pincode = pincode,
        phone = phone,
        isDefault = isDefault
    )
}

@Serializable
data class AddressInsertDto(
    @SerialName("user_id") val userId: String,
    val label: String,
    val name: String,
    @SerialName("address_line") val addressLine: String,
    val city: String,
    val state: String,
    val pincode: String,
    val phone: String,
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
data class AddressUpdateDto(
    val label: String,
    val name: String,
    @SerialName("address_line") val addressLine: String,
    val city: String,
    val state: String,
    val pincode: String,
    val phone: String,
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("image_url") val imageUrl: String = "",
) {
    fun toCategory() = Category(
        id = id,
        name = name,
        sortOrder = sortOrder,
        isActive = isActive,
        imageUrl = imageUrl,
    )
}

@Serializable
data class CategoryWriteDto(
    val name: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("image_url") val imageUrl: String = "",
)

@Serializable
data class CategoryActiveUpdateDto(
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class OrderDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val status: String = "placed",
    val subtotal: Int = 0,
    @SerialName("delivery_fee") val deliveryFee: Int = 0,
    @SerialName("extra_charges") val extraCharges: Int = 0,
    val discount: Int = 0,
    @SerialName("points_redeemed") val pointsRedeemed: Int = 0,
    @SerialName("points_discount") val pointsDiscount: Int = 0,
    @SerialName("points_earned") val pointsEarned: Int = 0,
    val total: Int = 0,
    @SerialName("payment_method") val paymentMethod: String = "",
    @SerialName("payment_status") val paymentStatus: String = "pending",
    @SerialName("delivery_name") val deliveryName: String = "",
    @SerialName("delivery_address_line") val deliveryAddressLine: String = "",
    @SerialName("delivery_city") val deliveryCity: String = "",
    @SerialName("delivery_state") val deliveryState: String = "",
    @SerialName("delivery_pincode") val deliveryPincode: String = "",
    @SerialName("delivery_phone") val deliveryPhone: String = "",
    @SerialName("coupon_code") val couponCode: String? = null,
    @SerialName("estimated_delivery") val estimatedDelivery: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("cancel_refund_choice") val cancelRefundChoice: String = "none",
    @SerialName("refund_status") val refundStatus: String = "none",
    @SerialName("refund_amount") val refundAmount: Int = 0,
    @SerialName("tracking_number") val trackingNumber: String? = null,
    val courier: String? = null,
    @SerialName("razorpay_order_id") val razorpayOrderId: String? = null,
    @SerialName("razorpay_payment_id") val razorpayPaymentId: String? = null,
)

@Serializable
data class OrderItemDto(
    val id: String = "",
    @SerialName("order_id") val orderId: String = "",
    @SerialName("item_id") val itemId: String? = null,
    val name: String,
    val price: Int,
    val quantity: Int,
    @SerialName("image_url") val imageUrl: String = "",
    val size: String = "",
) {
    fun toLineItem() = OrderLineItem(
        id = id,
        itemId = itemId,
        name = name,
        price = price,
        quantity = quantity,
        imageUrl = imageUrl,
        size = size,
    )
}

@Serializable
data class CouponDto(
    val id: String,
    val type: String = "DISCOUNT",
    val headline: String = "",
    val description: String = "",
    val code: String,
    @SerialName("valid_until") val validUntil: String? = null,
    @SerialName("discount_percent") val discountPercent: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("min_order") val minOrder: Int = 0,
    @SerialName("max_discount") val maxDiscount: Int? = null,
    @SerialName("fixed_amount") val fixedAmount: Int = 0,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("usage_limit") val usageLimit: Int? = null,
    @SerialName("per_user_limit") val perUserLimit: Int? = null,
) {
    fun toCoupon() = Coupon(
        id = id,
        type = runCatching { CouponType.valueOf(type) }.getOrDefault(CouponType.DISCOUNT),
        headline = headline,
        description = description,
        code = code,
        validUntil = validUntil ?: "",
        discountPercent = discountPercent,
        isActive = isActive,
        minOrder = minOrder,
        maxDiscount = maxDiscount,
        fixedAmount = fixedAmount,
        startsAt = startsAt,
        usageLimit = usageLimit,
        perUserLimit = perUserLimit,
    )
}

/** Null limits are written through [CouponsRepository] as explicit JSON nulls so they can be cleared. */
@Serializable
data class CouponWriteDto(
    val type: String,
    val headline: String,
    val description: String,
    val code: String,
    @SerialName("valid_until") val validUntil: String? = null,
    @SerialName("discount_percent") val discountPercent: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("min_order") val minOrder: Int = 0,
    @SerialName("max_discount") val maxDiscount: Int? = null,
    @SerialName("fixed_amount") val fixedAmount: Int = 0,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("usage_limit") val usageLimit: Int? = null,
    @SerialName("per_user_limit") val perUserLimit: Int? = null,
)

@Serializable
data class CouponActiveUpdateDto(
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class ReviewDto(
    val id: String,
    @SerialName("item_id") val itemId: String,
    @SerialName("user_id") val userId: String? = null,
    val author: String = "",
    @SerialName("author_initial") val authorInitial: String = "U",
    @SerialName("is_verified") val isVerified: Boolean = false,
    val rating: Int = 5,
    val text: String = "",
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    @SerialName("helpful_count") val helpfulCount: Int = 0,
    @SerialName("unhelpful_count") val unhelpfulCount: Int = 0,
    @SerialName("is_visible") val isVisible: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toReview() = Review(
        id = id,
        itemId = itemId,
        userId = userId,
        author = author,
        authorInitial = authorInitial.ifBlank { "U" },
        isVerified = isVerified,
        rating = rating,
        date = formatDate(createdAt),
        text = text,
        imageUrls = imageUrls,
        helpfulCount = helpfulCount,
        unhelpfulCount = unhelpfulCount,
        isVisible = isVisible,
    )
}

/** Author, verified flag and counters are set by a server trigger from the signed-in user. */
@Serializable
data class ReviewInsertDto(
    @SerialName("item_id") val itemId: String,
    @SerialName("user_id") val userId: String,
    val rating: Int,
    val text: String,
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
)

@Serializable
data class ReviewEditDto(
    val rating: Int,
    val text: String,
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
)

@Serializable
data class ReviewAdminUpdateDto(
    @SerialName("is_visible") val isVisible: Boolean,
    @SerialName("is_verified") val isVerified: Boolean,
)

@Serializable
data class NotificationDto(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val title: String,
    val message: String,
    val type: String = "GENERAL",
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("order_id") val orderId: String? = null,
) {
    fun toAppNotification() = AppNotification(
        id = id,
        title = title,
        message = message,
        timestamp = formatRelative(createdAt),
        type = runCatching { NotificationType.valueOf(type) }.getOrDefault(NotificationType.GENERAL),
        isRead = isRead,
        orderId = orderId,
        isBroadcast = userId == null,
    )
}

@Serializable
data class NotificationInsertDto(
    @SerialName("user_id") val userId: String? = null,
    val title: String,
    val message: String,
    val type: String = "GENERAL",
    @SerialName("is_read") val isRead: Boolean = false,
)

@Serializable
data class NotificationReadUpdateDto(
    @SerialName("is_read") val isRead: Boolean,
)

private val displayDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy")

fun formatDate(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        (runCatching { Instant.parse(iso) }.getOrNull() ?: java.time.OffsetDateTime.parse(iso).toInstant())
            .atZone(ZoneId.systemDefault()).toLocalDate().format(displayDateFormatter)
    }.getOrElse {
        runCatching { LocalDate.parse(iso.take(10)).format(displayDateFormatter) }.getOrDefault(iso)
    }
}

private fun parseInstant(iso: String): Instant? =
    runCatching { Instant.parse(iso) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()

fun formatRelative(iso: String?): String {
    if (iso.isNullOrBlank()) return "Just now"
    val instant = parseInstant(iso) ?: return formatDate(iso).ifBlank { "Just now" }
    val minutes = java.time.Duration.between(instant, Instant.now()).toMinutes()
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
        else -> formatDate(iso)
    }
}

private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")

fun formatDateTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val instant = parseInstant(iso) ?: return formatDate(iso)
    return instant.atZone(ZoneId.systemDefault()).format(dateTimeFormatter)
}

/** Parses a Postgres timestamptz / ISO string; null when blank or malformed. */
fun parseTimestamp(iso: String?): Instant? = iso?.takeIf { it.isNotBlank() }?.let(::parseInstant)

fun OrderDto.toOrder(lineItems: List<OrderLineItem>): Order {
    val primary = lineItems.firstOrNull()
    val product = Product(
        id = primary?.itemId.orEmpty(),
        name = when {
            primary == null -> "Order"
            lineItems.size > 1 -> "${primary.name} + ${lineItems.size - 1} more"
            else -> primary.name
        },
        description = "",
        tags = emptyList(),
        rating = 0f,
        reviewCount = 0,
        size = primary?.size.orEmpty(),
        price = primary?.price ?: total,
        imageUrl = primary?.imageUrl.orEmpty(),
        category = "",
    )
    return Order(
        id = id,
        product = product,
        quantity = lineItems.sumOf { it.quantity }.coerceAtLeast(1),
        totalPrice = total,
        placedDate = formatDate(createdAt),
        statuses = OrderStatusFlow.timeline(
            status = status,
            estimatedDelivery = estimatedDelivery,
            refundStatus = refundStatus,
            cancelRefundChoice = cancelRefundChoice,
        ),
        deliveryAddress = DeliveryAddress(
            name = deliveryName,
            addressLine = deliveryAddressLine,
            city = deliveryCity,
            state = deliveryState,
            pincode = deliveryPincode,
            phone = deliveryPhone,
        ),
        estimatedDelivery = estimatedDelivery,
        status = status,
        subtotal = subtotal,
        deliveryFee = deliveryFee,
        extraCharges = extraCharges,
        discount = discount,
        pointsRedeemed = pointsRedeemed,
        pointsDiscount = pointsDiscount,
        pointsEarned = pointsEarned,
        paymentMethod = paymentMethod,
        paymentStatus = paymentStatus,
        couponCode = couponCode,
        lineItems = lineItems,
        userId = userId.orEmpty(),
        cancelRefundChoice = cancelRefundChoice,
        refundStatus = refundStatus,
        refundAmount = refundAmount,
        cancelledAt = cancelledAt,
        deliveredAt = deliveredAt,
        paidAt = paidAt,
        expiresAt = expiresAt,
        createdAt = createdAt,
        trackingNumber = trackingNumber,
        courier = courier,
        razorpayOrderId = razorpayOrderId,
        razorpayPaymentId = razorpayPaymentId,
    )
}

package com.kindleaf.models

data class Coupon(
    val id: String,
    val type: CouponType,
    val headline: String,
    val description: String,
    val code: String,
    val validUntil: String,
    val discountPercent: Int = 0,
    val isActive: Boolean = true,
    val minOrder: Int = 0,
    val maxDiscount: Int? = null,
    /** Flat ₹ off; when > 0 it is used instead of [discountPercent]. */
    val fixedAmount: Int = 0,
    val startsAt: String? = null,
    val usageLimit: Int? = null,
    val perUserLimit: Int? = null,
)

enum class CouponType { DISCOUNT, SHIPPING, SPECIAL }

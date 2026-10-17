package com.kindleaf.data.remote

import com.kindleaf.data.SupabaseHelper
import com.kindleaf.models.Coupon
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

object CouponsRepository {

    suspend fun fetchActive(): List<Coupon> {
        return SupabaseHelper.client
            .from("coupons")
            .select {
                filter { eq("is_active", true) }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<CouponDto>()
            .map { it.toCoupon() }
            .filter { isStillValid(it.validUntil) }
    }

    suspend fun fetchAll(): List<Coupon> {
        return SupabaseHelper.client
            .from("coupons")
            .select {
                order("created_at", Order.DESCENDING)
            }
            .decodeList<CouponDto>()
            .map { it.toCoupon() }
    }

    suspend fun findByCode(code: String): Coupon? {
        return SupabaseHelper.client
            .from("coupons")
            .select {
                filter {
                    eq("code", code.trim().uppercase())
                    eq("is_active", true)
                }
                limit(1)
            }
            .decodeList<CouponDto>()
            .firstOrNull()
            ?.toCoupon()
            ?.takeIf { isStillValid(it.validUntil) }
    }

    suspend fun create(dto: CouponWriteDto): Coupon {
        return SupabaseHelper.client.from("coupons").insert(dto.toJson()) {
            select()
        }.decodeSingle<CouponDto>().toCoupon()
    }

    suspend fun update(id: String, dto: CouponWriteDto): Coupon {
        return SupabaseHelper.client.from("coupons").update(dto.toJson()) {
            filter { eq("id", id) }
            select()
        }.decodeSingle<CouponDto>().toCoupon()
    }

    /** Explicit JSON so optional limits can be cleared (the shared serializer drops nulls). */
    private fun CouponWriteDto.toJson(): JsonObject = buildJsonObject {
        put("type", type)
        put("headline", headline)
        put("description", description)
        put("code", code.trim().uppercase())
        put("valid_until", validUntil?.let(::JsonPrimitive) ?: JsonNull)
        put("discount_percent", discountPercent)
        put("is_active", isActive)
        put("min_order", minOrder)
        put("max_discount", maxDiscount?.let(::JsonPrimitive) ?: JsonNull)
        put("fixed_amount", fixedAmount)
        put("starts_at", startsAt?.let(::JsonPrimitive) ?: JsonNull)
        put("usage_limit", usageLimit?.let(::JsonPrimitive) ?: JsonNull)
        put("per_user_limit", perUserLimit?.let(::JsonPrimitive) ?: JsonNull)
    }

    suspend fun setActive(id: String, isActive: Boolean): Coupon {
        return SupabaseHelper.client.from("coupons").update(
            CouponActiveUpdateDto(isActive = isActive)
        ) {
            filter { eq("id", id) }
            select()
        }.decodeSingle<CouponDto>().toCoupon()
    }

    suspend fun delete(id: String) {
        SupabaseHelper.client.from("coupons").delete {
            filter { eq("id", id) }
        }
    }

    /** Short human summary of a coupon's rules, e.g. "Min order ₹499 · Max ₹100 off". */
    fun rulesSummary(coupon: Coupon): String = buildList {
        if (coupon.minOrder > 0) add("Min order ₹${coupon.minOrder}")
        coupon.maxDiscount?.takeIf { it > 0 }?.let { add("Max ₹$it off") }
        coupon.perUserLimit?.let { add(if (it == 1) "Once per customer" else "$it uses per customer") }
    }.joinToString(" · ")

    private fun isStillValid(validUntil: String): Boolean {
        if (validUntil.isBlank()) return true
        return runCatching {
            LocalDate.parse(validUntil.take(10)).isAfter(LocalDate.now().minusDays(1))
        }.getOrDefault(true)
    }
}

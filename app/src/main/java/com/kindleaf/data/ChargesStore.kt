package com.kindleaf.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.kindleaf.data.remote.ChargesRepository
import com.kindleaf.models.Charge

/**
 * Cached store/checkout charges (delivery + optional fees) from Supabase `charges`.
 */
object ChargesStore {

    val charges = mutableStateListOf<Charge>()
    val isLoading = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)

    private val fallbackDelivery = Charge(
        id = "local-delivery",
        key = "delivery",
        name = "Delivery Fee",
        amount = 40,
        isEnabled = true,
        freeAbove = 299,
        description = "Default delivery fee",
        sortOrder = 1,
    )

    suspend fun refresh() {
        isLoading.value = true
        error.value = null
        try {
            val remote = ChargesRepository.fetchAll()
            charges.clear()
            if (remote.isNotEmpty()) {
                charges.addAll(remote)
            } else {
                charges.add(fallbackDelivery)
            }
        } catch (e: Exception) {
            error.value = e.message
            if (charges.isEmpty()) {
                charges.add(fallbackDelivery)
            }
        } finally {
            isLoading.value = false
        }
    }

    fun deliveryCharge(): Charge? =
        charges.find { it.key.equals("delivery", ignoreCase = true) }

    fun extraCharges(): List<Charge> =
        charges.filterNot { it.key.equals("delivery", ignoreCase = true) }

    fun deliveryFeeFor(subtotal: Int): Int =
        deliveryCharge()?.amountFor(subtotal) ?: fallbackDelivery.amountFor(subtotal)

    fun extrasTotalFor(subtotal: Int): Int =
        extraCharges().sumOf { it.amountFor(subtotal) }

    fun appliedLines(subtotal: Int): List<Pair<Charge, Int>> {
        return charges.mapNotNull { charge ->
            val amount = charge.amountFor(subtotal)
            if (!charge.isEnabled) null
            else charge to amount
        }
    }
}

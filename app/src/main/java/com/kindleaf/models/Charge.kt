package com.kindleaf.models

data class Charge(
    val id: String,
    val key: String,
    val name: String,
    val amount: Int,
    val isEnabled: Boolean = true,
    val freeAbove: Int? = null,
    val description: String = "",
    val sortOrder: Int = 0,
) {
    fun amountFor(subtotal: Int): Int {
        if (!isEnabled) return 0
        val threshold = freeAbove
        if (threshold != null && subtotal >= threshold) return 0
        return amount.coerceAtLeast(0)
    }
}

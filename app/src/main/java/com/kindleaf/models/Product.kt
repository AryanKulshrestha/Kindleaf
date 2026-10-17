package com.kindleaf.models

data class Product(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val rating: Float,
    val reviewCount: Int,
    val size: String,
    val price: Int,
    val imageUrl: String,
    val isWishlisted: Boolean = false,
    val category: String = "All Teas",
    val categoryId: String? = null,
    val stockQty: Int = 0,
    val lowStockThreshold: Int = 5,
    /** Maximum retail price; shown struck-through when higher than [price]. */
    val mrp: Int? = null,
) {
    val isInStock: Boolean get() = stockQty > 0
    val isLowStock: Boolean get() = stockQty in 1..lowStockThreshold
    val discountPercent: Int
        get() = mrp?.takeIf { it > price && it > 0 }?.let { ((it - price) * 100) / it } ?: 0
}

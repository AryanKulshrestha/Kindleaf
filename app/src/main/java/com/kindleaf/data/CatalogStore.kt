package com.kindleaf.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.kindleaf.data.remote.CategoriesRepository
import com.kindleaf.data.remote.CouponsRepository
import com.kindleaf.data.remote.ItemsRepository
import com.kindleaf.models.Category
import com.kindleaf.models.Coupon
import com.kindleaf.models.Product

/**
 * In-memory catalog cache backed by Supabase `items` + `categories` + `coupons`.
 * No placeholder data: on failure the UI shows [error] with a retry.
 */
object CatalogStore {

    const val ALL_CATEGORIES = "All Teas"

    val products = mutableStateListOf<Product>()
    val isLoading = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val categories = mutableStateListOf(ALL_CATEGORIES)
    val categoryEntities = mutableStateListOf<Category>()
    val coupons = mutableStateListOf<Coupon>()

    suspend fun refresh() {
        isLoading.value = true
        error.value = null
        try {
            val items = ItemsRepository.fetchActiveItems()
            products.clear()
            products.addAll(items)

            val remoteCategories = runCatching { CategoriesRepository.fetchActive() }
                .getOrDefault(emptyList())
            categoryEntities.clear()
            categoryEntities.addAll(remoteCategories)

            categories.clear()
            categories.add(ALL_CATEGORIES)
            if (remoteCategories.isNotEmpty()) {
                categories.addAll(remoteCategories.map { it.name })
            } else {
                categories.addAll(
                    items.map { it.category }
                        .filter { it.isNotBlank() && !it.equals(ALL_CATEGORIES, ignoreCase = true) }
                        .distinct()
                        .sorted()
                )
            }

            refreshCoupons()
        } catch (e: Exception) {
            error.value = e.userMessage("Couldn't load products. Pull down to retry.")
        } finally {
            isLoading.value = false
        }
    }

    /** Refreshes without toggling the loading indicator (e.g. stock after an order). */
    suspend fun refreshQuietly() {
        runCatching { ItemsRepository.fetchActiveItems() }.onSuccess { items ->
            products.clear()
            products.addAll(items)
        }
    }

    suspend fun refreshCoupons() {
        runCatching { CouponsRepository.fetchActive() }.onSuccess { remote ->
            coupons.clear()
            coupons.addAll(remote)
        }
    }

    fun productById(id: String): Product? = products.find { it.id == id }

    /** Cached product, else fetched from the server (e.g. opened from a deep link). */
    suspend fun loadProduct(id: String): Product? =
        productById(id) ?: runCatching { ItemsRepository.fetchItem(id) }.getOrNull()

    fun filterByCategory(category: String): List<Product> {
        if (category == ALL_CATEGORIES) return products.toList()
        return products.filter { product ->
            product.category.equals(category, ignoreCase = true) ||
                product.tags.any { it.equals(category, ignoreCase = true) }
        }
    }
}

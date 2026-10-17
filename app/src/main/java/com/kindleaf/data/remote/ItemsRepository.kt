package com.kindleaf.data.remote

import com.kindleaf.data.SupabaseHelper
import com.kindleaf.models.Product
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ItemsRepository {

    suspend fun fetchActiveItems(): List<Product> {
        val rows = SupabaseHelper.client
            .from("items")
            .select {
                filter { eq("is_active", true) }
                order("created_at", Order.ASCENDING)
            }
            .decodeList<ItemDto>()
        return rows.map { it.toProduct() }
    }

    /**
     * Server-side search (name, description, tags, category + fuzzy name match).
     * [sort]: relevance, price_asc, price_desc, rating, popular, newest.
     */
    suspend fun search(
        query: String = "",
        category: String? = null,
        minPrice: Int? = null,
        maxPrice: Int? = null,
        sort: String = "relevance",
        inStockOnly: Boolean = false,
        limit: Int = 50,
        offset: Int = 0,
    ): List<Product> {
        return SupabaseHelper.client.postgrest.rpc(
            function = "search_items",
            parameters = buildJsonObject {
                put("p_query", query.trim())
                put("p_category", category)
                put("p_min_price", minPrice)
                put("p_max_price", maxPrice)
                put("p_sort", sort)
                put("p_in_stock_only", inStockOnly)
                put("p_limit", limit)
                put("p_offset", offset)
            }
        ).decodeList<ItemDto>().map { it.toProduct() }
    }

    suspend fun fetchAllItems(): List<ItemDto> {
        return SupabaseHelper.client
            .from("items")
            .select {
                order("created_at", Order.DESCENDING)
            }
            .decodeList()
    }

    /** Admin: fetch one item including inactive ones. */
    suspend fun fetchItemDto(id: String): ItemDto? {
        return SupabaseHelper.client
            .from("items")
            .select {
                filter { eq("id", id) }
                limit(1)
            }
            .decodeList<ItemDto>()
            .firstOrNull()
    }

    suspend fun fetchItem(id: String): Product? {
        return SupabaseHelper.client
            .from("items")
            .select {
                filter { eq("id", id) }
                limit(1)
            }
            .decodeList<ItemDto>()
            .firstOrNull()
            ?.toProduct()
    }

    suspend fun createItem(dto: ItemWriteDto): ItemDto {
        return SupabaseHelper.client.from("items").insert(dto) {
            select()
        }.decodeSingle()
    }

    suspend fun updateItem(id: String, dto: ItemWriteDto): ItemDto {
        return SupabaseHelper.client.from("items").update(dto) {
            filter { eq("id", id) }
            select()
        }.decodeSingle()
    }

    suspend fun setActive(id: String, isActive: Boolean): ItemDto {
        return SupabaseHelper.client.from("items").update(
            ItemActiveUpdateDto(isActive = isActive)
        ) {
            filter { eq("id", id) }
            select()
        }.decodeSingle()
    }

    suspend fun deleteItem(id: String) {
        SupabaseHelper.client.from("items").delete {
            filter { eq("id", id) }
        }
    }
}

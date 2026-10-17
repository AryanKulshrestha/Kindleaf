package com.kindleaf.data.remote

import com.kindleaf.data.SupabaseHelper
import com.kindleaf.models.Category
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order

object CategoriesRepository {

    suspend fun fetchActive(): List<Category> {
        return SupabaseHelper.client
            .from("categories")
            .select {
                filter { eq("is_active", true) }
                order("sort_order", Order.ASCENDING)
            }
            .decodeList<CategoryDto>()
            .map { it.toCategory() }
    }

    suspend fun fetchAll(): List<Category> {
        return SupabaseHelper.client
            .from("categories")
            .select {
                order("sort_order", Order.ASCENDING)
            }
            .decodeList<CategoryDto>()
            .map { it.toCategory() }
    }

    suspend fun create(dto: CategoryWriteDto): Category {
        return SupabaseHelper.client.from("categories").insert(dto) {
            select()
        }.decodeSingle<CategoryDto>().toCategory()
    }

    suspend fun update(id: String, dto: CategoryWriteDto): Category {
        return SupabaseHelper.client.from("categories").update(dto) {
            filter { eq("id", id) }
            select()
        }.decodeSingle<CategoryDto>().toCategory()
    }

    suspend fun setActive(id: String, isActive: Boolean): Category {
        return SupabaseHelper.client.from("categories").update(
            CategoryActiveUpdateDto(isActive = isActive)
        ) {
            filter { eq("id", id) }
            select()
        }.decodeSingle<CategoryDto>().toCategory()
    }

    suspend fun delete(id: String) {
        SupabaseHelper.client.from("categories").delete {
            filter { eq("id", id) }
        }
    }
}

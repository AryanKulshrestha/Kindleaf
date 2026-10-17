package com.kindleaf.data.remote

import com.kindleaf.data.SupabaseHelper
import com.kindleaf.models.Charge
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant

object ChargesRepository {

    suspend fun fetchAll(): List<Charge> {
        return SupabaseHelper.client
            .from("charges")
            .select {
                order("sort_order", Order.ASCENDING)
            }
            .decodeList<ChargeDto>()
            .map { it.toCharge() }
    }

    suspend fun create(dto: ChargeWriteDto): Charge {
        return SupabaseHelper.client.from("charges").insert(dto) {
            select()
        }.decodeSingle<ChargeDto>().toCharge()
    }

    suspend fun update(id: String, dto: ChargeWriteDto): Charge {
        return SupabaseHelper.client.from("charges").update(
            dto.copy(updatedAt = Instant.now().toString())
        ) {
            filter { eq("id", id) }
            select()
        }.decodeSingle<ChargeDto>().toCharge()
    }

    suspend fun setEnabled(id: String, isEnabled: Boolean): Charge {
        return SupabaseHelper.client.from("charges").update(
            ChargeEnabledUpdateDto(isEnabled = isEnabled)
        ) {
            filter { eq("id", id) }
            select()
        }.decodeSingle<ChargeDto>().toCharge()
    }

    suspend fun delete(id: String) {
        SupabaseHelper.client.from("charges").delete {
            filter { eq("id", id) }
        }
    }
}

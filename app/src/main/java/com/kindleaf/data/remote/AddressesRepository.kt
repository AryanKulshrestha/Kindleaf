package com.kindleaf.data.remote

import com.kindleaf.data.SupabaseHelper
import com.kindleaf.models.Address
import io.github.jan.supabase.postgrest.from

object AddressesRepository {

    suspend fun fetchAddresses(userId: String): List<Address> {
        return SupabaseHelper.client
            .from("addresses")
            .select {
                filter { eq("user_id", userId) }
            }
            .decodeList<AddressDto>()
            .map { it.toAddress() }
            .sortedByDescending { it.isDefault }
    }

    suspend fun addAddress(userId: String, address: Address): Address {
        val inserted = SupabaseHelper.client
            .from("addresses")
            .insert(
                AddressInsertDto(
                    userId = userId,
                    label = address.label,
                    name = address.name,
                    addressLine = address.addressLine,
                    city = address.city,
                    state = address.state,
                    pincode = address.pincode,
                    phone = address.phone,
                    isDefault = address.isDefault
                )
            ) {
                select()
            }
            .decodeSingle<AddressDto>()
        return inserted.toAddress()
    }

    suspend fun updateAddress(address: Address): Address {
        val updated = SupabaseHelper.client
            .from("addresses")
            .update(
                AddressUpdateDto(
                    label = address.label,
                    name = address.name,
                    addressLine = address.addressLine,
                    city = address.city,
                    state = address.state,
                    pincode = address.pincode,
                    phone = address.phone,
                    isDefault = address.isDefault
                )
            ) {
                filter { eq("id", address.id) }
                select()
            }
            .decodeSingle<AddressDto>()
        return updated.toAddress()
    }

    suspend fun deleteAddress(addressId: String) {
        SupabaseHelper.client.from("addresses").delete {
            filter { eq("id", addressId) }
        }
    }

    suspend fun setDefaultAddress(userId: String, addressId: String) {
        // Trigger clears other defaults when this row is set true
        SupabaseHelper.client.from("addresses").update(
            DefaultFlagDto(isDefault = true)
        ) {
            filter {
                eq("id", addressId)
                eq("user_id", userId)
            }
        }
    }
}

@kotlinx.serialization.Serializable
private data class DefaultFlagDto(
    @kotlinx.serialization.SerialName("is_default") val isDefault: Boolean
)

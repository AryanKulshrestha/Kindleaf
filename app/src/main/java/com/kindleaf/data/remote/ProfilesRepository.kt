package com.kindleaf.data.remote

import com.kindleaf.data.SupabaseHelper
import com.kindleaf.models.UserProfile
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant

object ProfilesRepository {

    suspend fun fetchProfile(userId: String): UserProfile? {
        return SupabaseHelper.client
            .from("profiles")
            .select {
                filter { eq("id", userId) }
                limit(1)
            }
            .decodeList<ProfileDto>()
            .firstOrNull()
            ?.toUserProfile()
    }

    suspend fun fetchAllProfiles(): List<UserProfile> {
        return SupabaseHelper.client
            .from("profiles")
            .select {
                order("created_at", Order.DESCENDING)
            }
            .decodeList<ProfileDto>()
            .map { it.toUserProfile() }
    }

    suspend fun ensureProfile(userId: String, name: String, email: String): UserProfile {
        fetchProfile(userId)?.let { return it }
        val initials = initialsFrom(name)
        val created = SupabaseHelper.client.from("profiles").insert(
            ProfileInsertDto(
                id = userId,
                name = name,
                email = email,
                phone = "",
                leafPoints = 0,
                avatarInitials = initials,
            )
        ) {
            select()
        }.decodeSingle<ProfileDto>()
        return created.toUserProfile()
    }

    /** Updates name and phone. Email changes go through [AuthRepository.changeEmail]. */
    suspend fun updateProfile(
        userId: String,
        name: String,
        phone: String,
    ): UserProfile {
        val updated = SupabaseHelper.client.from("profiles").update(
            ProfileUpdateDto(
                name = name.trim(),
                phone = phone.trim(),
                avatarInitials = initialsFrom(name),
                updatedAt = Instant.now().toString()
            )
        ) {
            filter { eq("id", userId) }
            select()
        }.decodeSingle<ProfileDto>()
        return updated.toUserProfile()
    }

    private fun initialsFrom(name: String): String {
        return name.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifEmpty { "U" }
    }
}

package com.kindleaf.data.remote

import com.kindleaf.data.SupabaseHelper
import com.kindleaf.models.Review
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order

object ReviewsRepository {

    suspend fun fetchForItem(itemId: String): List<Review> {
        return SupabaseHelper.client
            .from("reviews")
            .select {
                filter {
                    eq("item_id", itemId)
                    eq("is_visible", true)
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<ReviewDto>()
            .map { it.toReview() }
    }

    /** The signed-in customer's own review of an item, if any (visible or hidden). */
    suspend fun fetchMine(itemId: String, userId: String): Review? {
        return SupabaseHelper.client
            .from("reviews")
            .select {
                filter {
                    eq("item_id", itemId)
                    eq("user_id", userId)
                }
                limit(1)
            }
            .decodeList<ReviewDto>()
            .firstOrNull()
            ?.toReview()
    }

    suspend fun fetchAll(): List<Review> {
        return SupabaseHelper.client
            .from("reviews")
            .select {
                order("created_at", Order.DESCENDING)
                limit(300)
            }
            .decodeList<ReviewDto>()
            .map { it.toReview() }
    }

    /**
     * Customers can review items from delivered orders only (enforced by the server, which also sets
     * the author name and the verified-purchase flag).
     */
    suspend fun create(
        itemId: String,
        userId: String,
        rating: Int,
        text: String,
        imageUrls: List<String> = emptyList(),
    ): Review {
        return SupabaseHelper.client.from("reviews").insert(
            ReviewInsertDto(
                itemId = itemId,
                userId = userId,
                rating = rating.coerceIn(1, 5),
                text = text.trim(),
                imageUrls = imageUrls,
            )
        ) {
            select()
        }.decodeSingle<ReviewDto>().toReview()
    }

    suspend fun updateMine(id: String, rating: Int, text: String, imageUrls: List<String>): Review {
        return SupabaseHelper.client.from("reviews").update(
            ReviewEditDto(rating = rating.coerceIn(1, 5), text = text.trim(), imageUrls = imageUrls)
        ) {
            filter { eq("id", id) }
            select()
        }.decodeSingle<ReviewDto>().toReview()
    }

    /** Review ids the user voted on, mapped to whether the vote was "helpful". */
    suspend fun fetchMyVotes(userId: String, reviewIds: List<String>): Map<String, Boolean> {
        if (reviewIds.isEmpty()) return emptyMap()
        return SupabaseHelper.client.from("review_votes")
            .select {
                filter {
                    eq("user_id", userId)
                    isIn("review_id", reviewIds)
                }
            }
            .decodeList<ReviewVoteDto>()
            .associate { it.reviewId to it.isHelpful }
    }

    /** Casts or changes a vote; pass null to remove it. Counts are recomputed by the server. */
    suspend fun vote(reviewId: String, userId: String, helpful: Boolean?) {
        if (helpful == null) {
            SupabaseHelper.client.from("review_votes").delete {
                filter {
                    eq("review_id", reviewId)
                    eq("user_id", userId)
                }
            }
        } else {
            SupabaseHelper.client.from("review_votes").upsert(
                ReviewVoteDto(reviewId = reviewId, userId = userId, isHelpful = helpful)
            ) {
                onConflict = "review_id,user_id"
            }
        }
    }

    suspend fun updateAdmin(id: String, isVisible: Boolean, isVerified: Boolean): Review {
        return SupabaseHelper.client.from("reviews").update(
            ReviewAdminUpdateDto(isVisible = isVisible, isVerified = isVerified)
        ) {
            filter { eq("id", id) }
            select()
        }.decodeSingle<ReviewDto>().toReview()
    }

    suspend fun delete(id: String) {
        SupabaseHelper.client.from("reviews").delete {
            filter { eq("id", id) }
        }
    }
}

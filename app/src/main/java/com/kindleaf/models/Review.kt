package com.kindleaf.models

data class Review(
    val id: String,
    val itemId: String = "",
    val userId: String? = null,
    val author: String,
    val authorInitial: String,
    val isVerified: Boolean,
    val rating: Int,
    val date: String,
    val text: String,
    val imageUrls: List<String> = emptyList(),
    val helpfulCount: Int = 0,
    val unhelpfulCount: Int = 0,
    val isVisible: Boolean = true,
)

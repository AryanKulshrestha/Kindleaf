package com.kindleaf.models

data class Category(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val imageUrl: String = "",
)

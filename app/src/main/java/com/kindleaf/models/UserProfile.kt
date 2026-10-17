package com.kindleaf.models

data class UserProfile(
    val id: String = "",
    val name: String,
    val phone: String,
    val email: String,
    val leafPoints: Int,
    val avatarInitials: String,
    val role: String = "customer",
) {
    val isAdmin: Boolean get() = role == "admin"
}

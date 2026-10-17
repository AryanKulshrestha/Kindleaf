package com.kindleaf.models

data class Address(
    val id: String,
    val label: String, // "Home", "Work", "Other"
    val name: String,
    val addressLine: String,
    val city: String,
    val state: String,
    val pincode: String,
    val phone: String,
    val isDefault: Boolean = false
)

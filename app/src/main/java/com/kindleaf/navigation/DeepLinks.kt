package com.kindleaf.navigation

import android.content.Intent
import androidx.compose.runtime.mutableStateOf

/** Deep link waiting to be opened once the user is signed in (e.g. from a notification tap). */
sealed interface DeepLink {
    data class OrderLink(val orderId: String) : DeepLink
    data class ProductLink(val productId: String) : DeepLink
}

object DeepLinks {

    val pending = mutableStateOf<DeepLink?>(null)

    /** Accepts kindleaf://order/{id} and kindleaf://product/{id}. */
    fun capture(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "kindleaf") return
        val id = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        pending.value = when (uri.host) {
            "order" -> DeepLink.OrderLink(id)
            "product" -> DeepLink.ProductLink(id)
            else -> null
        }
    }

    fun consume(): DeepLink? = pending.value.also { pending.value = null }
}

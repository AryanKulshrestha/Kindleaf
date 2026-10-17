package com.kindleaf.data

import com.kindleaf.data.remote.AddressesRepository
import com.kindleaf.data.remote.AuthRepository
import com.kindleaf.data.remote.NotificationsRepository
import com.kindleaf.data.remote.OrdersRepository
import com.kindleaf.data.remote.ProfilesRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object SessionManager {

    /** Loads everything a signed-in user needs, in parallel. The role comes only from the database. */
    suspend fun bootstrapAfterAuth() = coroutineScope {
        val catalog = async { CatalogStore.refresh() }
        val charges = async { runCatching { ChargesStore.refresh() } }
        val settings = async { StoreSettingsStore.refresh() }

        val user = AuthRepository.currentUser() ?: run {
            catalog.await(); charges.await(); settings.await()
            return@coroutineScope
        }
        val metaName = user.userMetadata?.get("name")?.toString()?.trim('"')
            ?: user.userMetadata?.get("full_name")?.toString()?.trim('"')
            ?: user.email?.substringBefore("@")
            ?: "User"

        val profile = async {
            runCatching {
                ProfilesRepository.ensureProfile(
                    userId = user.id,
                    name = metaName,
                    email = user.email.orEmpty()
                )
            }.getOrNull()
        }
        val addresses = async { runCatching { AddressesRepository.fetchAddresses(user.id) }.getOrNull() }
        val orders = async { runCatching { OrdersRepository.fetchForUser(user.id) }.getOrNull() }
        val notifications = async { runCatching { NotificationsRepository.fetchForUser(user.id) }.getOrNull() }

        profile.await()?.let { AppStateManager.setLoggedInUser(it) }
        addresses.await()?.let { AppStateManager.replaceAddresses(it) }
        orders.await()?.let { AppStateManager.replaceOrders(it) }
        notifications.await()?.let { AppStateManager.replaceNotifications(it) }

        // Cart rows are matched against the catalog, so load them after products.
        catalog.await()
        charges.await()
        settings.await()
        AppStateManager.loadRemoteCartAndWishlist(user.id)
    }

    suspend fun logout() {
        runCatching { AuthRepository.signOut() }
        AppStateManager.clearUserSession()
    }
}

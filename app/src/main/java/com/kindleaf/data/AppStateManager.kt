package com.kindleaf.data

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.kindleaf.data.remote.AuthRepository
import com.kindleaf.data.remote.CartRepository
import com.kindleaf.data.remote.NotificationsRepository
import com.kindleaf.data.remote.OrdersRepository
import com.kindleaf.data.remote.ProfilesRepository
import com.kindleaf.data.remote.WishlistRepository
import com.kindleaf.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Central app state (singleton) holding Compose-observable session data.
 * Cart and wishlist are kept in sync with Supabase so they survive restarts and devices;
 * all prices and totals are computed by the server at checkout.
 */
object AppStateManager {

    private const val TAG = "AppState"

    /** Fire-and-forget sync of local changes; UI state updates optimistically. */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun currentUserId(): String? =
        userProfile.value.id.ifBlank { null } ?: runCatching { AuthRepository.currentUser()?.id }.getOrNull()

    private fun sync(label: String, block: suspend (userId: String) -> Unit) {
        val userId = currentUserId() ?: return
        syncScope.launch {
            runCatching { block(userId) }.onFailure { Log.w(TAG, "$label sync failed: ${it.message}") }
        }
    }

    // ── Wishlist ──
    val wishlistIds = mutableStateListOf<String>()

    fun toggleWishlist(productId: String) {
        if (productId in wishlistIds) {
            wishlistIds.remove(productId)
            sync("wishlist remove") { WishlistRepository.remove(it, productId) }
        } else {
            wishlistIds.add(productId)
            sync("wishlist add") { WishlistRepository.add(it, productId) }
        }
    }

    fun isWishlisted(productId: String): Boolean = productId in wishlistIds

    // ── User Profile ──
    var userProfile = mutableStateOf(
        UserProfile(
            id = "",
            name = "",
            phone = "",
            email = "",
            leafPoints = 0,
            avatarInitials = "U",
            role = "customer",
        )
    )
        private set

    fun setLoggedInUser(profile: UserProfile) {
        userProfile.value = profile
    }

    suspend fun refreshProfile() {
        val userId = currentUserId() ?: return
        runCatching { ProfilesRepository.fetchProfile(userId) }
            .onSuccess { profile -> if (profile != null) setLoggedInUser(profile) }
    }

    fun clearUserSession() {
        userProfile.value = UserProfile(
            id = "",
            name = "",
            phone = "",
            email = "",
            leafPoints = 0,
            avatarInitials = "U",
            role = "customer",
        )
        addresses.clear()
        wishlistIds.clear()
        cartItems.clear()
        orders.clear()
        notifications.clear()
    }

    // ── Addresses ──
    val addresses = mutableStateListOf<Address>()

    fun replaceAddresses(list: List<Address>) {
        addresses.clear()
        addresses.addAll(list)
    }

    fun addAddress(address: Address) {
        if (address.isDefault) {
            val updated = addresses.map { it.copy(isDefault = false) }
            addresses.clear()
            addresses.addAll(updated)
        }
        addresses.add(address)
    }

    fun updateAddress(address: Address) {
        val index = addresses.indexOfFirst { it.id == address.id }
        if (index != -1) {
            if (address.isDefault) {
                val updated = addresses.mapIndexed { i, a ->
                    if (i == index) address else a.copy(isDefault = false)
                }
                addresses.clear()
                addresses.addAll(updated)
            } else {
                addresses[index] = address
            }
        }
    }

    fun deleteAddress(addressId: String) {
        addresses.removeAll { it.id == addressId }
    }

    fun setDefaultAddress(addressId: String) {
        val updated = addresses.map {
            it.copy(isDefault = it.id == addressId)
        }
        addresses.clear()
        addresses.addAll(updated)
    }

    fun getDefaultAddress(): Address? = addresses.find { it.isDefault } ?: addresses.firstOrNull()

    // ── Notifications ──
    val notifications = mutableStateListOf<AppNotification>()

    val unreadNotificationCount: Int
        get() = notifications.count { !it.isRead }

    fun replaceNotifications(list: List<AppNotification>) {
        notifications.clear()
        notifications.addAll(list)
    }

    suspend fun refreshNotifications() {
        val userId = currentUserId() ?: return
        runCatching { NotificationsRepository.fetchForUser(userId) }
            .onSuccess { replaceNotifications(it) }
    }

    fun markNotificationRead(notificationId: String) {
        val index = notifications.indexOfFirst { it.id == notificationId }
        if (index != -1) {
            notifications[index] = notifications[index].copy(isRead = true)
        }
    }

    fun markAllNotificationsRead() {
        val updated = notifications.map { it.copy(isRead = true) }
        notifications.clear()
        notifications.addAll(updated)
    }

    suspend fun syncMarkNotificationRead(notificationId: String) {
        markNotificationRead(notificationId)
        runCatching { NotificationsRepository.markRead(notificationId) }
    }

    suspend fun syncMarkAllNotificationsRead() {
        markAllNotificationsRead()
        val userId = currentUserId() ?: return
        runCatching { NotificationsRepository.markAllRead(userId) }
    }

    /** Deletes a customer's own notification (broadcasts can only be hidden locally). */
    suspend fun deleteNotification(notification: AppNotification) {
        notifications.removeAll { it.id == notification.id }
        if (!notification.isBroadcast) {
            runCatching { NotificationsRepository.delete(notification.id) }
        }
    }

    // ── Cart ──
    val cartItems = mutableStateListOf<CartItem>()

    val cartCount: Int get() = cartItems.sumOf { it.quantity }

    /** Largest quantity allowed for a product: limited by stock and the store's per-item cap. */
    fun maxQuantityFor(product: Product): Int {
        val cap = StoreSettingsStore.settings.maxQuantityPerItem
        return minOf(cap, product.stockQty).coerceAtLeast(0)
    }

    /** Adds to the cart, clamped to available stock. Returns false if nothing could be added. */
    fun addToCart(product: Product, quantity: Int = 1): Boolean {
        val max = maxQuantityFor(product)
        if (max <= 0) return false
        val existing = cartItems.indexOfFirst { it.product.id == product.id }
        val newQuantity = if (existing != -1) {
            (cartItems[existing].quantity + quantity).coerceAtMost(max)
        } else {
            quantity.coerceIn(1, max)
        }
        if (existing != -1) {
            if (cartItems[existing].quantity == newQuantity) return false
            cartItems[existing] = cartItems[existing].copy(product = product, quantity = newQuantity)
        } else {
            cartItems.add(CartItem(product = product, quantity = newQuantity))
        }
        sync("cart add") { CartRepository.setQuantity(it, product.id, newQuantity) }
        return true
    }

    fun updateCartQuantity(productId: String, newQuantity: Int) {
        val index = cartItems.indexOfFirst { it.product.id == productId }
        if (index == -1) return
        if (newQuantity <= 0) {
            cartItems.removeAt(index)
            sync("cart remove") { CartRepository.remove(it, productId) }
            return
        }
        val clamped = newQuantity.coerceAtMost(maxQuantityFor(cartItems[index].product).coerceAtLeast(1))
        cartItems[index] = cartItems[index].copy(quantity = clamped)
        sync("cart update") { CartRepository.setQuantity(it, productId, clamped) }
    }

    fun removeFromCart(productId: String) {
        cartItems.removeAll { it.product.id == productId }
        sync("cart remove") { CartRepository.remove(it, productId) }
    }

    fun clearCart() {
        cartItems.clear()
        sync("cart clear") { CartRepository.clear(it) }
    }

    /** Replaces the local cart with the server copy, re-reading products from the catalog. */
    suspend fun loadRemoteCartAndWishlist(userId: String) {
        runCatching { CartRepository.fetch(userId) }.onSuccess { rows ->
            val restored = rows.mapNotNull { row ->
                CatalogStore.productById(row.itemId)?.let { CartItem(it, row.quantity) }
            }
            cartItems.clear()
            cartItems.addAll(restored)
        }
        runCatching { WishlistRepository.fetch(userId) }.onSuccess { ids ->
            wishlistIds.clear()
            wishlistIds.addAll(ids)
        }
    }

    /** Local estimate for the cart screen only. Checkout always shows the server quote. */
    val cartSubtotal: Int get() = cartItems.sumOf { it.product.price * it.quantity }
    val cartDeliveryFee: Int get() = ChargesStore.deliveryFeeFor(cartSubtotal)
    val cartExtraCharges: Int get() = ChargesStore.extrasTotalFor(cartSubtotal)
    val cartChargesTotal: Int get() = cartDeliveryFee + cartExtraCharges
    val cartTotal: Int get() = cartSubtotal + cartChargesTotal

    // ── Orders ──
    val orders = mutableStateListOf<Order>()

    fun replaceOrders(list: List<Order>) {
        orders.clear()
        orders.addAll(list)
    }

    /** Puts a fresh copy of an order into the local list (after payment, cancel, etc.). */
    fun upsertOrder(order: Order) {
        val index = orders.indexOfFirst { it.id == order.id }
        if (index >= 0) orders[index] = order else orders.add(0, order)
    }

    /**
     * Places the order on the server (prices, coupon, points and stock are all validated there).
     * The server also empties the saved cart. Online orders come back as `pending_payment`.
     */
    suspend fun placeOrder(
        address: Address,
        paymentMethod: PaymentMethod,
        couponCode: String? = null,
        redeemPoints: Int = 0,
    ): Order {
        val order = OrdersRepository.placeOrder(
            addressId = address.id,
            cartItems = cartItems.toList(),
            paymentMethod = paymentMethod,
            couponCode = couponCode,
            redeemPoints = redeemPoints,
        )
        upsertOrder(order)
        cartItems.clear()
        refreshProfile()
        refreshNotifications()
        CatalogStore.refreshQuietly()
        return order
    }

    suspend fun cancelOrder(orderId: String, refundChoice: String): Order {
        val updated = OrdersRepository.cancelOrder(orderId, refundChoice)
        upsertOrder(updated)
        refreshProfile()
        refreshNotifications()
        return updated
    }

    suspend fun refreshOrders() {
        val userId = currentUserId() ?: return
        runCatching { OrdersRepository.fetchForUser(userId) }
            .onSuccess { replaceOrders(it) }
    }
}

data class CartItem(val product: Product, val quantity: Int)

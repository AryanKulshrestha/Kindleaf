package com.kindleaf.ui.checkout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.CatalogStore
import com.kindleaf.data.StoreSettingsStore
import com.kindleaf.data.remote.OrdersRepository
import com.kindleaf.data.userMessage
import com.kindleaf.models.Address
import com.kindleaf.models.CartQuote
import com.kindleaf.models.Order
import com.kindleaf.models.PaymentMethod
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CheckoutUiState(
    val quote: CartQuote? = null,
    val quoteLoading: Boolean = true,
    val quoteError: String? = null,
    val selectedAddressId: String? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.ONLINE,
    val couponInput: String = "",
    val appliedCoupon: String? = null,
    val couponError: String? = null,
    val couponChecking: Boolean = false,
    val usePoints: Boolean = false,
    val placing: Boolean = false,
    val placeError: String? = null,
) {
    val canPlaceOrder: Boolean
        get() = !placing && !quoteLoading && selectedAddressId != null &&
            quote != null && quote.errors.isEmpty() && quote.lines.isNotEmpty() &&
            (paymentMethod != PaymentMethod.COD || quote.codEnabled) &&
            (paymentMethod != PaymentMethod.ONLINE || quote.onlineEnabled || quote.total == 0)
}

/** Result of placing an order: either done (COD / fully covered) or needs online payment. */
sealed interface PlaceOrderResult {
    data class Placed(val order: Order) : PlaceOrderResult
    data class NeedsPayment(val order: Order) : PlaceOrderResult
}

/**
 * Checkout state. Every number shown comes from the server `quote_cart` RPC, which applies the same
 * rules `place_order` enforces (stock, coupon limits, Leaf Points cap, charges).
 */
class CheckoutViewModel : ViewModel() {

    private val _state = MutableStateFlow(CheckoutUiState())
    val state: StateFlow<CheckoutUiState> = _state.asStateFlow()

    private var quoteJob: Job? = null

    init {
        val settings = StoreSettingsStore.settings
        _state.update {
            it.copy(
                selectedAddressId = AppStateManager.getDefaultAddress()?.id,
                paymentMethod = if (settings.onlinePaymentsEnabled || !settings.codEnabled) PaymentMethod.ONLINE else PaymentMethod.COD,
            )
        }
        viewModelScope.launch {
            StoreSettingsStore.refresh()
            CatalogStore.refreshCoupons()
        }
        refreshQuote()
    }

    fun selectAddress(address: Address) = _state.update { it.copy(selectedAddressId = address.id) }

    /** Keeps the selection valid when addresses change (e.g. the first one was just added). */
    fun syncAddresses(addresses: List<Address>) {
        val current = _state.value.selectedAddressId
        if (current == null || addresses.none { it.id == current }) {
            val fallback = addresses.find { it.isDefault } ?: addresses.lastOrNull()
            _state.update { it.copy(selectedAddressId = fallback?.id) }
        }
    }

    fun selectPayment(method: PaymentMethod) = _state.update { it.copy(paymentMethod = method, placeError = null) }

    fun onCouponInput(value: String) =
        _state.update { it.copy(couponInput = value.uppercase().filter { c -> !c.isWhitespace() }, couponError = null) }

    fun applyCoupon(code: String = _state.value.couponInput) {
        val trimmed = code.trim().uppercase()
        if (trimmed.isBlank()) return
        _state.update { it.copy(couponInput = trimmed, couponChecking = true, couponError = null) }
        // A slower in-flight quote must not overwrite (and drop) the coupon being applied.
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            runCatching {
                OrdersRepository.quote(AppStateManager.cartItems.toList(), trimmed, requestedPoints())
            }.onSuccess { quote ->
                if (quote.couponError != null || quote.couponCode == null) {
                    // Still a valid quote of the cart (without the rejected coupon).
                    _state.update {
                        it.copy(
                            couponChecking = false,
                            couponError = quote.couponError ?: "Invalid coupon code",
                            quote = quote,
                            quoteError = null,
                            quoteLoading = false,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            couponChecking = false,
                            appliedCoupon = quote.couponCode,
                            quote = quote,
                            quoteError = null,
                            quoteLoading = false,
                        )
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(couponChecking = false, couponError = e.userMessage("Couldn't check this coupon")) }
                // The quote refresh this replaced was cancelled; run it again.
                refreshQuote()
            }
        }
    }

    fun removeCoupon() {
        _state.update { it.copy(appliedCoupon = null, couponInput = "", couponError = null) }
        refreshQuote()
    }

    fun setUsePoints(use: Boolean) {
        _state.update { it.copy(usePoints = use) }
        refreshQuote()
    }

    private fun requestedPoints(): Int =
        if (_state.value.usePoints) AppStateManager.userProfile.value.leafPoints else 0

    fun refreshQuote() {
        quoteJob?.cancel()
        _state.update { it.copy(quoteLoading = true, quoteError = null) }
        quoteJob = viewModelScope.launch {
            delay(120)
            runCatching {
                OrdersRepository.quote(
                    cartItems = AppStateManager.cartItems.toList(),
                    couponCode = _state.value.appliedCoupon,
                    redeemPoints = requestedPoints(),
                )
            }.onSuccess { quote ->
                _state.update { current ->
                    // A previously applied coupon can stop qualifying (e.g. usage limit reached).
                    val couponDropped = current.appliedCoupon != null && quote.couponCode == null
                    val method = when {
                        current.paymentMethod == PaymentMethod.ONLINE && !quote.onlineEnabled && quote.codEnabled -> PaymentMethod.COD
                        current.paymentMethod == PaymentMethod.COD && !quote.codEnabled && quote.onlineEnabled -> PaymentMethod.ONLINE
                        else -> current.paymentMethod
                    }
                    current.copy(
                        quote = quote,
                        quoteLoading = false,
                        appliedCoupon = if (couponDropped) null else current.appliedCoupon,
                        couponError = if (couponDropped) quote.couponError else current.couponError,
                        paymentMethod = method,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(quoteLoading = false, quoteError = e.userMessage("Couldn't calculate your total")) }
            }
        }
    }

    fun placeOrder(address: Address, onResult: (PlaceOrderResult) -> Unit) {
        val current = _state.value
        if (!current.canPlaceOrder) return
        _state.update { it.copy(placing = true, placeError = null) }
        viewModelScope.launch {
            runCatching {
                AppStateManager.placeOrder(
                    address = address,
                    paymentMethod = current.paymentMethod,
                    couponCode = current.appliedCoupon,
                    redeemPoints = if (current.usePoints) current.quote?.pointsApplied ?: 0 else 0,
                )
            }.onSuccess { order ->
                _state.update { it.copy(placing = false) }
                onResult(
                    if (order.isAwaitingPayment) PlaceOrderResult.NeedsPayment(order)
                    else PlaceOrderResult.Placed(order)
                )
            }.onFailure { e ->
                _state.update { it.copy(placing = false, placeError = e.userMessage("Couldn't place your order")) }
                // Stock or prices may have changed; show the latest numbers.
                refreshQuote()
            }
        }
    }
}

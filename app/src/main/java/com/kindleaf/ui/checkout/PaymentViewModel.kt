package com.kindleaf.ui.checkout

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kindleaf.data.AppStateManager
import com.kindleaf.data.EdgeFunctionException
import com.kindleaf.data.StoreSettingsStore
import com.kindleaf.data.remote.OrdersRepository
import com.kindleaf.data.remote.PaymentsRepository
import com.kindleaf.data.remote.parseTimestamp
import com.kindleaf.data.userMessage
import com.kindleaf.models.Order
import com.kindleaf.payments.RazorpayCheckoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

enum class PaymentPhase {
    /** Loading the order. */
    Loading,
    /** Order is awaiting payment; the Pay button is available. */
    Ready,
    /** Creating the Razorpay order on the server. */
    Preparing,
    /** Razorpay Checkout is open. */
    InCheckout,
    /** Checkout reported success; verifying the signature on the server. */
    Verifying,
    /** Verification call failed, but the webhook may still confirm the payment. */
    Confirming,
    /** Order is paid (or no longer needs payment). */
    Paid,
    /** Payment window elapsed or the order was cancelled. */
    Closed,
    /** The order could not be loaded. */
    LoadFailed,
}

data class PaymentUiState(
    val phase: PaymentPhase = PaymentPhase.Loading,
    val order: Order? = null,
    val message: String? = null,
    val isError: Boolean = false,
    /** Online payment isn't set up on the server (Razorpay keys missing). */
    val paymentsUnavailable: Boolean = false,
    val secondsLeft: Long? = null,
    val busy: Boolean = false,
)

class PaymentViewModel(private val orderId: String) : ViewModel() {

    private val _state = MutableStateFlow(PaymentUiState())
    val state: StateFlow<PaymentUiState> = _state.asStateFlow()

    private var countdownJob: Job? = null

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(phase = PaymentPhase.Loading, message = null, isError = false) }
        viewModelScope.launch {
            runCatching { OrdersRepository.fetchById(orderId) }
                .onSuccess { order ->
                    if (order == null) {
                        _state.update { it.copy(phase = PaymentPhase.LoadFailed, message = "Order not found", isError = true) }
                    } else {
                        applyOrder(order)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(phase = PaymentPhase.LoadFailed, message = e.userMessage(), isError = true) }
                }
        }
    }

    /** Re-checks the order, e.g. when returning to the app after the Razorpay screen. */
    fun refreshSilently() {
        val phase = _state.value.phase
        if (phase == PaymentPhase.InCheckout || phase == PaymentPhase.Verifying || phase == PaymentPhase.Preparing) return
        viewModelScope.launch {
            runCatching { OrdersRepository.fetchById(orderId) }.getOrNull()?.let { applyOrder(it) }
        }
    }

    private fun isExpired(order: Order): Boolean =
        parseTimestamp(order.expiresAt)?.isBefore(Instant.now()) == true

    private fun applyOrder(order: Order) {
        AppStateManager.upsertOrder(order)
        val phase = when {
            order.isPaid || (!order.isAwaitingPayment && order.status != "cancelled") -> PaymentPhase.Paid
            order.status == "cancelled" -> PaymentPhase.Closed
            // Still pending on the server until the expiry job runs, but it can no longer be paid.
            isExpired(order) -> PaymentPhase.Closed
            else -> PaymentPhase.Ready
        }
        _state.update {
            it.copy(
                order = order,
                phase = if (it.phase == PaymentPhase.Confirming && phase == PaymentPhase.Ready) PaymentPhase.Confirming else phase,
                message = when (phase) {
                    PaymentPhase.Closed -> "This order was cancelled because payment wasn't completed in time."
                    else -> it.message
                },
                isError = phase == PaymentPhase.Closed || it.isError,
            )
        }
        if (phase == PaymentPhase.Ready) startCountdown(order) else countdownJob?.cancel()
    }

    private fun startCountdown(order: Order) {
        val expiresAt = parseTimestamp(order.expiresAt) ?: return
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (isActive) {
                val left = Duration.between(Instant.now(), expiresAt).seconds.coerceAtLeast(0)
                _state.update { it.copy(secondsLeft = left) }
                if (left == 0L) {
                    _state.update {
                        it.copy(
                            phase = PaymentPhase.Closed,
                            message = "The payment window for this order has ended. Please place the order again.",
                            isError = true,
                        )
                    }
                    break
                }
                delay(1_000)
            }
        }
    }

    fun pay(activity: Activity) {
        val order = _state.value.order ?: return
        if (_state.value.phase != PaymentPhase.Ready) return
        _state.update { it.copy(phase = PaymentPhase.Preparing, message = null, isError = false) }

        viewModelScope.launch {
            val info = try {
                PaymentsRepository.createRazorpayOrder(order.id)
            } catch (e: Exception) {
                // 503: Razorpay keys not set; 404 + NOT_DEPLOYED text: functions not deployed.
                val unavailable = e is EdgeFunctionException &&
                    (e.statusCode == 503 || (e.statusCode == 404 && e.message.orEmpty().startsWith("Online payments are not set up")))
                val closed = e is EdgeFunctionException && (e.statusCode == 409 || e.statusCode == 410)
                _state.update {
                    it.copy(
                        phase = PaymentPhase.Ready,
                        message = e.userMessage("Couldn't start the payment. Please try again."),
                        isError = true,
                        paymentsUnavailable = unavailable,
                    )
                }
                if (closed) refreshSilently()
                return@launch
            }

            _state.update { it.copy(phase = PaymentPhase.InCheckout) }
            when (val result = RazorpayCheckoutManager.pay(activity, info)) {
                is RazorpayCheckoutManager.Result.Success -> verify(result)
                is RazorpayCheckoutManager.Result.Cancelled -> {
                    _state.update {
                        it.copy(phase = PaymentPhase.Ready, message = "Payment cancelled. You can try again.", isError = false)
                    }
                    // A payment can still complete in the background (e.g. UPI collect request).
                    refreshSilently()
                }
                is RazorpayCheckoutManager.Result.Failed -> {
                    _state.update { it.copy(phase = PaymentPhase.Ready, message = result.message, isError = true) }
                    refreshSilently()
                }
            }
        }
    }

    private suspend fun verify(result: RazorpayCheckoutManager.Result.Success) {
        _state.update { it.copy(phase = PaymentPhase.Verifying, message = null, isError = false) }
        runCatching {
            PaymentsRepository.verifyPayment(
                orderId = orderId,
                razorpayOrderId = result.razorpayOrderId,
                razorpayPaymentId = result.razorpayPaymentId,
                signature = result.signature,
            )
        }.onSuccess { paid ->
            onPaid(paid)
        }.onFailure {
            // The webhook confirms payments server-to-server; poll for it before giving up.
            waitForWebhookConfirmation()
        }
    }

    private suspend fun waitForWebhookConfirmation() {
        _state.update {
            it.copy(
                phase = PaymentPhase.Confirming,
                message = "We're confirming your payment with the bank. This can take a few seconds…",
                isError = false,
            )
        }
        repeat(12) {
            delay(5_000)
            val latest = runCatching { OrdersRepository.fetchById(orderId) }.getOrNull()
            if (latest?.isPaid == true) {
                onPaid(latest)
                return
            }
        }
        _state.update {
            it.copy(
                phase = PaymentPhase.Confirming,
                message = "We haven't received confirmation yet. If money was deducted, your order will be " +
                    "confirmed automatically (or refunded) — check My Orders in a few minutes.",
                isError = false,
            )
        }
    }

    private suspend fun onPaid(order: Order) {
        countdownJob?.cancel()
        AppStateManager.upsertOrder(order)
        _state.update { it.copy(order = order, phase = PaymentPhase.Paid, message = null, isError = false) }
        AppStateManager.refreshNotifications()
        AppStateManager.refreshProfile()
    }

    fun switchToCod() {
        if (!StoreSettingsStore.settings.codEnabled) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { OrdersRepository.switchToCod(orderId) }
                .onSuccess { order ->
                    countdownJob?.cancel()
                    AppStateManager.upsertOrder(order)
                    AppStateManager.refreshNotifications()
                    _state.update { it.copy(order = order, phase = PaymentPhase.Paid, busy = false) }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, message = e.userMessage(), isError = true) }
                    refreshSilently()
                }
        }
    }

    fun cancelOrder(onCancelled: () -> Unit) {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { AppStateManager.cancelOrder(orderId, "none") }
                .onSuccess {
                    countdownJob?.cancel()
                    _state.update { it.copy(busy = false) }
                    onCancelled()
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, message = e.userMessage(), isError = true) }
                }
        }
    }

    class Factory(private val orderId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PaymentViewModel(orderId) as T
    }
}

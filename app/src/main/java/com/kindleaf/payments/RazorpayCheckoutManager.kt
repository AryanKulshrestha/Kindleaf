package com.kindleaf.payments

import android.app.Activity
import com.kindleaf.models.RazorpayCheckoutInfo
import com.razorpay.Checkout
import com.razorpay.PaymentData
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

/**
 * Bridges Razorpay's Activity callbacks ([com.kindleaf.MainActivity] implements
 * PaymentResultWithDataListener) into a suspend call.
 *
 * The result from here is only a hint: the order is marked paid on the server after the
 * signature is verified (razorpay-verify-payment) or the webhook arrives.
 */
object RazorpayCheckoutManager {

    sealed interface Result {
        data class Success(
            val razorpayPaymentId: String,
            val razorpayOrderId: String,
            val signature: String,
        ) : Result

        data class Cancelled(val message: String) : Result
        data class Failed(val message: String) : Result
    }

    private var pending: CompletableDeferred<Result>? = null

    private const val BRAND_COLOR = "#1F9C3A"

    suspend fun pay(activity: Activity, info: RazorpayCheckoutInfo): Result {
        pending?.complete(Result.Cancelled("Payment was restarted"))
        val deferred = CompletableDeferred<Result>()
        pending = deferred

        val options = JSONObject().apply {
            put("name", info.name)
            put("description", info.description)
            put("order_id", info.razorpayOrderId)
            put("currency", info.currency)
            put("amount", info.amountPaise)
            put("send_sms_hash", true)
            put("theme", JSONObject().put("color", BRAND_COLOR))
            put("retry", JSONObject().put("enabled", true).put("max_count", 3))
            put(
                "prefill",
                JSONObject().apply {
                    if (info.prefillName.isNotBlank()) put("name", info.prefillName)
                    if (info.prefillEmail.isNotBlank()) put("email", info.prefillEmail)
                    if (info.prefillContact.isNotBlank()) put("contact", info.prefillContact)
                }
            )
            put("notes", JSONObject().put("kindleaf_order_id", info.orderId))
        }

        try {
            Checkout().apply { setKeyID(info.keyId) }.open(activity, options)
        } catch (e: Exception) {
            pending = null
            return Result.Failed(e.message ?: "Could not open the payment screen")
        }
        return deferred.await()
    }

    /** Called from MainActivity.onPaymentSuccess. */
    fun onPaymentSuccess(razorpayPaymentId: String?, data: PaymentData?) {
        val deferred = pending ?: return
        pending = null
        val paymentId = data?.paymentId ?: razorpayPaymentId
        val orderId = data?.orderId
        val signature = data?.signature
        deferred.complete(
            if (paymentId.isNullOrBlank() || orderId.isNullOrBlank() || signature.isNullOrBlank()) {
                Result.Failed("Payment response was incomplete. If money was deducted, it will be confirmed automatically.")
            } else {
                Result.Success(paymentId, orderId, signature)
            }
        )
    }

    /** Called from MainActivity.onPaymentError. */
    fun onPaymentError(code: Int, response: String?, @Suppress("UNUSED_PARAMETER") data: PaymentData?) {
        val deferred = pending ?: return
        pending = null
        val description = runCatching {
            JSONObject(response.orEmpty()).optJSONObject("error")?.optString("description")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

        deferred.complete(
            when (code) {
                Checkout.PAYMENT_CANCELED -> Result.Cancelled(description ?: "Payment cancelled")
                Checkout.NETWORK_ERROR -> Result.Failed("Network error during payment. Please try again.")
                else -> Result.Failed(description ?: "Payment failed. Please try again.")
            }
        )
    }

    fun preload(activity: Activity) {
        runCatching { Checkout.preload(activity.applicationContext) }
    }
}

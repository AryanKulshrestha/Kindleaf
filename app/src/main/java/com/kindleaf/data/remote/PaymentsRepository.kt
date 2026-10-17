package com.kindleaf.data.remote

import com.kindleaf.data.EdgeFunctionException
import com.kindleaf.models.Order
import com.kindleaf.models.RazorpayCheckoutInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Talks to the Razorpay Edge Functions in `supabase/functions`. The Razorpay secret key lives only
 * in Supabase secrets; the app never sees it.
 */
object PaymentsRepository {

    private val json get() = EdgeFunctions.json

    /** Creates (or reuses) the Razorpay order for a `pending_payment` order. */
    suspend fun createRazorpayOrder(orderId: String): RazorpayCheckoutInfo {
        val body = invoke("razorpay-create-order", buildJsonObject { put("order_id", orderId) })
        return json.decodeFromString<RazorpayCheckoutDto>(body).toModel()
    }

    /** Verifies the checkout result server-side and returns the now-paid order. */
    suspend fun verifyPayment(
        orderId: String,
        razorpayOrderId: String,
        razorpayPaymentId: String,
        signature: String,
    ): Order {
        val body = invoke(
            "razorpay-verify-payment",
            buildJsonObject {
                put("order_id", orderId)
                put("razorpay_order_id", razorpayOrderId)
                put("razorpay_payment_id", razorpayPaymentId)
                put("razorpay_signature", signature)
            }
        )
        val dto = json.decodeFromString<EdgeOrderResponse>(body).order
        return OrdersRepository.attachLineItems(listOf(dto)).first()
    }

    /** Admin: send the pending refund for an online order back through Razorpay. */
    suspend fun refundViaRazorpay(orderId: String): Order {
        val body = invoke("razorpay-refund", buildJsonObject { put("order_id", orderId) })
        val dto = json.decodeFromString<EdgeOrderResponse>(body).order
        return OrdersRepository.attachLineItems(listOf(dto)).first()
    }

    private suspend fun invoke(function: String, payload: JsonObject): String = try {
        EdgeFunctions.invoke(function, payload)
    } catch (e: EdgeFunctionException) {
        // The gateway's own 404 (no `error` body) means the payment functions were never deployed.
        if (e.statusCode == 404 && e.message == EdgeFunctions.NOT_DEPLOYED) {
            throw EdgeFunctionException("Online payments are not set up yet. Please use Cash on Delivery.", 404)
        }
        throw e
    }
}

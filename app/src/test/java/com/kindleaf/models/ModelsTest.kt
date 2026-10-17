package com.kindleaf.models

import com.kindleaf.data.EdgeFunctionException
import com.kindleaf.data.remote.OrderDto
import com.kindleaf.data.remote.formatDate
import com.kindleaf.data.remote.parseTimestamp
import com.kindleaf.data.remote.toOrder
import com.kindleaf.data.userMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OrderStatusFlowTest {

    @Test
    fun `next status follows the fulfilment steps`() {
        assertEquals("confirmed", OrderStatusFlow.nextStatus("placed"))
        assertEquals("delivered", OrderStatusFlow.nextStatus("out_for_delivery"))
        assertNull(OrderStatusFlow.nextStatus("delivered"))
        assertNull(OrderStatusFlow.nextStatus("pending_payment"))
        assertNull(OrderStatusFlow.nextStatus("cancelled"))
    }

    @Test
    fun `timeline marks steps up to the current one`() {
        val timeline = OrderStatusFlow.timeline(status = "packed", estimatedDelivery = "29 Sep 2026")
        assertEquals(6, timeline.size)
        assertTrue(timeline.take(3).all { it.isCompleted })
        assertTrue(timeline[2].isCurrent)
        assertFalse(timeline[3].isCompleted)
        assertEquals("Estimated 29 Sep 2026", timeline.last().subtitle)
    }

    @Test
    fun `special states collapse to a single step`() {
        assertEquals("Awaiting payment", OrderStatusFlow.timeline("pending_payment", "").single().title)
        val cancelled = OrderStatusFlow.timeline("cancelled", "", refundStatus = "completed", cancelRefundChoice = "leaf_points")
        assertEquals("Cancelled · Leaf Points credited", cancelled.single().subtitle)
        assertEquals("Returned", OrderStatusFlow.timeline("returned", "").single().title)
    }

    @Test
    fun `labels cover every status`() {
        OrderStatusFlow.allStatuses.forEach { status ->
            assertFalse("label for $status", OrderStatusFlow.label(status).contains('_'))
        }
    }
}

class OrderTest {

    private fun order(status: String, paymentStatus: String, total: Int = 500) = OrderDto(
        id = "abcdef12-3456-7890-abcd-ef1234567890",
        status = status,
        paymentStatus = paymentStatus,
        total = total,
        paymentMethod = if (paymentStatus == "cod_pending") "Cash on Delivery" else "Online Payment",
    ).toOrder(
        listOf(
            OrderLineItem(itemId = "i1", name = "Tulsi Green Tea", price = 199, quantity = 2),
            OrderLineItem(itemId = "i2", name = "Chamomile Calm Tea", price = 249, quantity = 1),
        )
    )

    @Test
    fun `only paid orders need a refund choice`() {
        assertTrue(order("placed", "paid").needsRefundChoice)
        assertFalse(order("placed", "cod_pending").needsRefundChoice)
        assertFalse(order("pending_payment", "pending").needsRefundChoice)
        assertFalse(order("placed", "paid", total = 0).needsRefundChoice)
    }

    @Test
    fun `cancellation window matches the server rules`() {
        assertTrue(order("pending_payment", "pending").canCancel)
        assertTrue(order("confirmed", "paid").canCancel)
        assertFalse(order("packed", "paid").canCancel)
        assertFalse(order("delivered", "paid").canCancel)
    }

    @Test
    fun `multi item orders summarise every line`() {
        val o = order("placed", "paid")
        assertEquals("Tulsi Green Tea + 1 more", o.product.name)
        assertEquals(3, o.itemCount)
        assertEquals("ABCDEF12", o.shortId)
        assertEquals(398, o.lineItems.first().lineTotal)
    }

    @Test
    fun `leaf point refund is ten points per rupee`() {
        assertEquals(5000, order("placed", "paid", total = 500).leafPointsRefundAmount)
    }
}

class ProductTest {

    private fun product(price: Int, mrp: Int?, stock: Int) = Product(
        id = "p", name = "Tea", description = "", tags = emptyList(), rating = 0f, reviewCount = 0,
        size = "", price = price, imageUrl = "", stockQty = stock, lowStockThreshold = 5, mrp = mrp,
    )

    @Test
    fun `discount percent uses mrp only when higher than price`() {
        assertEquals(20, product(price = 200, mrp = 250, stock = 10).discountPercent)
        assertEquals(0, product(price = 200, mrp = 150, stock = 10).discountPercent)
        assertEquals(0, product(price = 200, mrp = null, stock = 10).discountPercent)
    }

    @Test
    fun `stock flags`() {
        assertFalse(product(100, null, 0).isInStock)
        assertTrue(product(100, null, 3).isLowStock)
        assertFalse(product(100, null, 50).isLowStock)
    }
}

class FormattingTest {

    @Test
    fun `parses postgres timestamps with offsets`() {
        assertNotNull(parseTimestamp("2026-09-24T10:15:30.123456+00:00"))
        assertNotNull(parseTimestamp("2026-09-24T10:15:30Z"))
        assertNull(parseTimestamp(""))
        assertNull(parseTimestamp("not a date"))
    }

    @Test
    fun `formats dates for display`() {
        assertEquals("", formatDate(null))
        assertTrue(formatDate("2026-09-24T10:15:30+00:00").endsWith("Sep 2026"))
    }

    @Test
    fun `user messages never leak request details`() {
        val leaky = RuntimeException("Bad request\nURL: https://x.supabase.co/rest/v1/orders\nHeaders: apikey=...")
        assertEquals("Bad request", leaky.userMessage())
        assertEquals("Network error. Please try again.", IOException("socket closed").userMessage())
        assertEquals("Online payments are not set up", EdgeFunctionException("Online payments are not set up", 503).userMessage())
        assertEquals("fallback", RuntimeException("https://leak.example").userMessage("fallback"))
    }
}

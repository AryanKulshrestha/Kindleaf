package com.kindleaf.ui.orders

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.kindleaf.data.StoreSettingsStore
import com.kindleaf.data.remote.formatDate
import com.kindleaf.data.remote.formatDateTime
import com.kindleaf.models.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Builds a simple A4 invoice PDF for an order and opens / shares it through the app's FileProvider. */
object InvoicePdf {

    private const val PAGE_WIDTH = 595   // A4 in PostScript points
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val BOTTOM_LIMIT = PAGE_HEIGHT - 60f

    /** Orders that reached "placed" or later (not awaiting payment, not cancelled) have an invoice. */
    fun isAvailable(order: Order): Boolean =
        order.status in com.kindleaf.models.OrderStatusFlow.steps.map { it.first } || order.status == "returned"

    suspend fun generate(context: Context, order: Order): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "invoices").apply { mkdirs() }
        val file = File(dir, "Invoice-${order.shortId}.pdf")
        val document = PdfDocument()
        try {
            Writer(document, order).draw()
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }
        file
    }

    /** Opens the PDF in a viewer; falls back to the share sheet when no viewer is installed. */
    fun openOrShare(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share invoice"))
        }
    }

    private class Writer(private val document: PdfDocument, private val order: Order) {
        private var pageNumber = 0
        private lateinit var page: PdfDocument.Page
        private lateinit var canvas: Canvas
        private var y = 0f

        private val dark = Color.rgb(30, 30, 30)
        private val muted = Color.rgb(107, 114, 128)
        private val green = Color.rgb(31, 156, 58)
        private val line = Color.rgb(220, 220, 220)

        private fun paint(size: Float, bold: Boolean = false, color: Int = dark, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
                textAlign = align
            }

        private val body = paint(10f)
        private val bodyBold = paint(10f, bold = true)
        private val small = paint(8.5f, color = muted)
        private val right = paint(10f, align = Paint.Align.RIGHT)
        private val rightBold = paint(10f, bold = true, align = Paint.Align.RIGHT)
        private val header = paint(9f, bold = true, color = muted)
        private val headerRight = paint(9f, bold = true, color = muted, align = Paint.Align.RIGHT)
        private val linePaint = Paint().apply { color = line; strokeWidth = 1f }

        // Table columns (x positions)
        private val colItem = MARGIN
        private val colSize = 300f
        private val colQty = 390f
        private val colPrice = 470f
        private val colAmount = PAGE_WIDTH - MARGIN

        fun draw() {
            newPage()
            drawHeader()
            drawParties()
            drawItems()
            drawTotals()
            drawFooter()
            document.finishPage(page)
        }

        private fun newPage() {
            if (pageNumber > 0) document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            y = MARGIN + 10f
            if (pageNumber > 1) {
                canvas.drawText("Invoice INV-${order.shortId} (continued)", MARGIN, y, small)
                y += 20f
            }
        }

        private fun ensureSpace(height: Float) {
            if (y + height > BOTTOM_LIMIT) newPage()
        }

        private fun drawHeader() {
            canvas.drawText("Kindleaf", MARGIN, y + 16f, paint(24f, bold = true, color = green))
            canvas.drawText("INVOICE", colAmount, y + 16f, paint(18f, bold = true, align = Paint.Align.RIGHT))
            y += 34f
            canvas.drawText("Premium teas, delivered", MARGIN, y, small)
            val support = StoreSettingsStore.settings.supportEmail
            if (support.isNotBlank()) canvas.drawText(support, colAmount, y, paint(8.5f, color = muted, align = Paint.Align.RIGHT))
            y += 22f

            val today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
            val rows = listOf(
                "Invoice number" to "INV-${order.shortId}",
                "Order ID" to "#${order.shortId}",
                "Order date" to (formatDateTime(order.createdAt).ifBlank { order.placedDate }),
                "Invoice date" to today,
                "Payment method" to order.paymentMethod.ifBlank { "—" },
                "Payment status" to order.paymentStatusLabel,
            ) + listOfNotNull(
                order.deliveredAt?.let { "Delivered on" to formatDate(it) },
                order.refundStatusLabel?.let { "Refund" to it },
            )
            rows.forEach { (label, value) ->
                canvas.drawText(label, MARGIN, y, small)
                canvas.drawText(value, MARGIN + 110f, y, body)
                y += 15f
            }
            y += 8f
            canvas.drawLine(MARGIN, y, colAmount, y, linePaint)
            y += 20f
        }

        private fun drawParties() {
            val a = order.deliveryAddress
            val lines = buildList {
                if (a.name.isNotBlank()) add(a.name)
                addAll(wrap(a.addressLine, body, 240f))
                val cityLine = listOf(a.city, a.state).filter { it.isNotBlank() }.joinToString(", ")
                val withPin = if (a.pincode.isNotBlank()) "$cityLine - ${a.pincode}" else cityLine
                if (withPin.isNotBlank()) add(withPin)
                if (a.phone.isNotBlank()) add("Phone: ${a.phone}")
            }
            canvas.drawText("BILL TO", MARGIN, y, header)
            canvas.drawText("SHIP TO", 320f, y, header)
            y += 15f
            lines.forEachIndexed { i, text ->
                val p = if (i == 0) bodyBold else body
                canvas.drawText(text, MARGIN, y, p)
                canvas.drawText(text, 320f, y, p)
                y += 14f
            }
            y += 16f
        }

        private fun drawTableHeader() {
            canvas.drawText("ITEM", colItem, y, header)
            canvas.drawText("SIZE", colSize, y, header)
            canvas.drawText("QTY", colQty, y, header)
            canvas.drawText("PRICE", colPrice, y, headerRight.apply { textAlign = Paint.Align.RIGHT })
            canvas.drawText("AMOUNT", colAmount, y, headerRight)
            y += 6f
            canvas.drawLine(MARGIN, y, colAmount, y, linePaint)
            y += 16f
        }

        private fun drawItems() {
            drawTableHeader()
            val items = order.lineItems
            if (items.isEmpty()) {
                canvas.drawText(order.product.name, colItem, y, body)
                canvas.drawText("${order.quantity}", colQty, y, body)
                canvas.drawText("₹${order.subtotal.takeIf { it > 0 } ?: order.totalPrice}", colAmount, y, right)
                y += 18f
            }
            items.forEach { item ->
                val nameLines = wrap(item.name, body, colSize - colItem - 12f)
                val height = nameLines.size * 13f + 8f
                if (y + height > BOTTOM_LIMIT) {
                    newPage()
                    drawTableHeader()
                }
                canvas.drawText(item.size.ifBlank { "—" }, colSize, y, body)
                canvas.drawText("${item.quantity}", colQty, y, body)
                canvas.drawText("₹${item.price}", colPrice, y, right)
                canvas.drawText("₹${item.lineTotal}", colAmount, y, right)
                nameLines.forEach { l ->
                    canvas.drawText(l, colItem, y, body)
                    y += 13f
                }
                y += 5f
                canvas.drawLine(MARGIN, y - 4f, colAmount, y - 4f, linePaint.apply { alpha = 120 })
                linePaint.alpha = 255
                y += 6f
            }
            y += 6f
        }

        private fun drawTotals() {
            val rows = buildList {
                add("Subtotal" to "₹${order.subtotal}")
                add("Delivery fee" to if (order.deliveryFee == 0) "Free" else "₹${order.deliveryFee}")
                if (order.extraCharges > 0) add("Other charges" to "₹${order.extraCharges}")
                if (order.discount > 0) {
                    val code = order.couponCode?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    add("Coupon discount$code" to "−₹${order.discount}")
                }
                if (order.pointsDiscount > 0) add("Leaf Points (${order.pointsRedeemed} pts)" to "−₹${order.pointsDiscount}")
            }
            ensureSpace(rows.size * 16f + 60f)
            val labelX = 330f
            rows.forEach { (label, value) ->
                canvas.drawText(label, labelX, y, body)
                canvas.drawText(value, colAmount, y, right)
                y += 16f
            }
            y += 2f
            canvas.drawLine(labelX, y, colAmount, y, linePaint)
            y += 18f
            canvas.drawText("Total", labelX, y, paint(12f, bold = true))
            canvas.drawText("₹${order.totalPrice}", colAmount, y, paint(12f, bold = true, align = Paint.Align.RIGHT))
            y += 16f
            canvas.drawText(
                if (order.isPaid) "Paid" else order.paymentStatusLabel,
                colAmount, y, paint(9f, color = if (order.isPaid) green else muted, align = Paint.Align.RIGHT)
            )
            y += 30f
        }

        private fun drawFooter() {
            ensureSpace(40f)
            canvas.drawText("Thank you for shopping with Kindleaf.", MARGIN, y, bodyBold)
            y += 14f
            canvas.drawText("All amounts are in Indian Rupees (INR). This is a computer-generated invoice.", MARGIN, y, small)
        }

        /** Greedy word wrap to [maxWidth]. */
        private fun wrap(text: String, p: Paint, maxWidth: Float): List<String> {
            if (text.isBlank()) return emptyList()
            val result = mutableListOf<String>()
            var current = ""
            text.split(Regex("\\s+")).forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (p.measureText(candidate) <= maxWidth) {
                    current = candidate
                } else {
                    if (current.isNotEmpty()) result += current
                    current = word
                    // Hard-break very long words.
                    while (p.measureText(current) > maxWidth && current.length > 1) {
                        var cut = current.length - 1
                        while (cut > 1 && p.measureText(current.take(cut)) > maxWidth) cut--
                        result += current.take(cut)
                        current = current.drop(cut)
                    }
                }
            }
            if (current.isNotEmpty()) result += current
            return result
        }
    }
}

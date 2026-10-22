package br.com.timasters.app.data.business.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.core.format.MoneyInput
import br.com.timasters.app.core.validation.DocumentValidator
import br.com.timasters.app.data.business.ClientEntity
import br.com.timasters.app.data.business.CompanyProfileEntity
import br.com.timasters.app.data.business.OrderKind
import br.com.timasters.app.data.business.PhotoStage
import br.com.timasters.app.data.business.PricingSettingsEntity
import br.com.timasters.app.data.business.ServiceOrderEntity
import java.io.File
import java.time.ZoneId

/** Everything printed on a quote / service order. */
data class OrderPdfData(
    val order: ServiceOrderEntity,
    val client: ClientEntity,
    val company: CompanyProfileEntity?,
    val settings: PricingSettingsEntity?,
    val logo: Bitmap?,
    val ticketLabel: String?,
    val zone: ZoneId,
    /** Before/after photos, already shrunk for printing. */
    val photos: List<Pair<PhotoStage, Bitmap>> = emptyList(),
    /** The client's signature drawn on the phone, if any. */
    val signature: Bitmap? = null
)

/**
 * Draws an A4 PDF with the company brand (logo and color), client, items, labor and totals.
 * Long item lists continue on new pages. Photos come after the notes. Service orders (and
 * signed quotes) end with signature lines, with the client's drawn signature when there is one.
 */
class OrderPdfWriter(private val context: Context) {

    private val accent = Color.rgb(0x15, 0x65, 0xC0)
    private val textColor = Color.rgb(0x21, 0x21, 0x21)
    private val mutedColor = Color.rgb(0x61, 0x61, 0x61)
    private val lineColor = Color.rgb(0xE0, 0xE0, 0xE0)

    private fun paint(size: Float, bold: Boolean = false, color: Int = textColor) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun write(data: OrderPdfData, file: File) {
        val brand = data.company?.primaryColor?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: accent
        val document = PdfDocument()
        val page = PageCursor(document, brand)
        try {
            drawHeader(page, data, brand)
            drawClient(page, data)
            drawItems(page, data)
            drawTotals(page, data, brand)
            drawNotes(page, data)
            drawPhotos(page, data)
            if (data.order.orderKind == OrderKind.SERVICE_ORDER || data.signature != null) drawSignatures(page, data)
            page.finish()
            file.parentFile?.mkdirs()
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    // region Sections

    private fun drawHeader(page: PageCursor, data: OrderPdfData, brand: Int) {
        val canvas = page.canvas
        val company = data.company
        var textLeft = MARGIN
        data.logo?.let { logo ->
            val scale = minOf(LOGO_MAX_W / logo.width, LOGO_MAX_H / logo.height, 1f)
            val w = logo.width * scale
            val h = logo.height * scale
            canvas.drawBitmap(logo, null, RectF(MARGIN, page.y, MARGIN + w, page.y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            textLeft = MARGIN + w + 12f
        }
        val titleWidth = 190f
        val companyWidth = PAGE_W - MARGIN - titleWidth - textLeft - 8f
        var y = page.y
        y += drawText(canvas, company?.name ?: s(R.string.app_name), textLeft, y, companyWidth, paint(14f, bold = true))
        val details = listOfNotNull(
            company?.document?.let { DocumentValidator.format(it) },
            company?.phone?.let { BrFormatters.formatPhone(it) },
            company?.email,
            listOfNotNull(company?.address, company?.city).joinToString(" · ").ifEmpty { null }
        )
        details.forEach { y += drawText(canvas, it, textLeft, y + 1f, companyWidth, paint(9f, color = mutedColor)) + 1f }

        val order = data.order
        val title = s(if (order.orderKind == OrderKind.QUOTE) R.string.pdf_title_quote else R.string.pdf_title_service_order)
        val number = order.number?.let { s(R.string.pdf_number, it) } ?: s(R.string.pdf_ref, order.id.take(8).uppercase())
        val right = PAGE_W - MARGIN
        var ty = page.y
        ty += drawText(canvas, title, right - titleWidth, ty, titleWidth, paint(15f, bold = true, color = brand), Layout.Alignment.ALIGN_OPPOSITE)
        ty += drawText(canvas, number, right - titleWidth, ty, titleWidth, paint(11f, bold = true), Layout.Alignment.ALIGN_OPPOSITE)
        ty += drawText(
            canvas, s(R.string.pdf_issued_at, BrFormatters.formatDate(order.createdAt, data.zone)),
            right - titleWidth, ty + 2f, titleWidth, paint(9f, color = mutedColor), Layout.Alignment.ALIGN_OPPOSITE
        )
        order.validUntil?.let {
            ty += drawText(
                canvas, s(R.string.pdf_valid_until, BrFormatters.formatDate(it, data.zone)),
                right - titleWidth, ty + 2f, titleWidth, paint(9f, color = mutedColor), Layout.Alignment.ALIGN_OPPOSITE
            )
        }
        val logoBottom = page.y + (data.logo?.let { minOf(LOGO_MAX_W / it.width, LOGO_MAX_H / it.height, 1f) * it.height } ?: 0f)
        page.y = maxOf(y, ty, logoBottom) + 10f
        canvas.drawRect(MARGIN, page.y, PAGE_W - MARGIN, page.y + 3f, Paint().apply { color = brand })
        page.y += 16f
    }

    private fun drawClient(page: PageCursor, data: OrderPdfData) {
        val client = data.client
        page.y += drawText(page.canvas, s(R.string.pdf_client), MARGIN, page.y, CONTENT_W, paint(9f, bold = true, color = mutedColor))
        page.y += drawText(page.canvas, client.name, MARGIN, page.y + 2f, CONTENT_W, paint(12f, bold = true)) + 2f
        val lines = listOfNotNull(
            client.docNumber?.let {
                s(if (client.docType == "cnpj") R.string.pdf_cnpj else R.string.pdf_cpf, DocumentValidator.format(it))
            },
            listOfNotNull(client.phone?.let { BrFormatters.formatPhone(it) }, client.email).joinToString(" · ").ifEmpty { null },
            listOfNotNull(client.address, client.city).joinToString(", ").ifEmpty { null },
            data.ticketLabel?.let { s(R.string.pdf_ticket, it) }
        )
        lines.forEach { page.y += drawText(page.canvas, it, MARGIN, page.y + 1f, CONTENT_W, paint(10f)) + 1f }
        page.y += 14f
    }

    private fun drawItems(page: PageCursor, data: OrderPdfData) {
        val items = data.order.items
        if (items.isEmpty()) return
        drawItemsHeader(page)
        items.forEach { item ->
            val descPaint = paint(10f)
            val descHeight = measure(item.description, COL_DESC_W, descPaint)
            page.ensureSpace(descHeight + 10f) { drawItemsHeader(it) }
            val canvas = page.canvas
            val y = page.y + 4f
            drawText(canvas, item.description, MARGIN, y, COL_DESC_W, descPaint)
            drawText(canvas, MoneyInput.formatQuantity(item.quantity) + " " + item.unit, COL_QTY_X, y, COL_QTY_W, paint(10f), Layout.Alignment.ALIGN_OPPOSITE)
            drawText(canvas, MoneyInput.formatCents(item.unitPriceCents), COL_UNIT_X, y, COL_MONEY_W, paint(10f), Layout.Alignment.ALIGN_OPPOSITE)
            drawText(canvas, MoneyInput.formatCents(item.totalCents), COL_TOTAL_X, y, COL_MONEY_W, paint(10f), Layout.Alignment.ALIGN_OPPOSITE)
            page.y = y + descHeight + 4f
            canvas.drawLine(MARGIN, page.y, PAGE_W - MARGIN, page.y, Paint().apply { color = lineColor })
        }
        page.y += 10f
    }

    private fun drawItemsHeader(page: PageCursor) {
        val canvas = page.canvas
        canvas.drawRect(MARGIN, page.y, PAGE_W - MARGIN, page.y + 18f, Paint().apply { color = Color.rgb(0xF5, 0xF5, 0xF5) })
        val p = paint(9f, bold = true, color = mutedColor)
        val y = page.y + 4f
        drawText(canvas, s(R.string.pdf_col_description), MARGIN + 4f, y, COL_DESC_W, p)
        drawText(canvas, s(R.string.pdf_col_quantity), COL_QTY_X, y, COL_QTY_W, p, Layout.Alignment.ALIGN_OPPOSITE)
        drawText(canvas, s(R.string.pdf_col_unit_price), COL_UNIT_X, y, COL_MONEY_W, p, Layout.Alignment.ALIGN_OPPOSITE)
        drawText(canvas, s(R.string.pdf_col_total), COL_TOTAL_X, y, COL_MONEY_W, p, Layout.Alignment.ALIGN_OPPOSITE)
        page.y += 20f
    }

    private fun drawTotals(page: PageCursor, data: OrderPdfData, brand: Int) {
        val order = data.order
        val rows = buildList {
            if (order.items.isNotEmpty()) add(s(R.string.pdf_items_subtotal) to MoneyInput.formatCents(order.itemsCents))
            if (order.laborCents > 0) {
                add(
                    s(
                        R.string.pdf_labor,
                        MoneyInput.minutesToInput(order.laborMinutes),
                        MoneyInput.formatCents(order.hourlyRateCents)
                    ) to MoneyInput.formatCents(order.laborCents)
                )
            }
            if (order.urgencyCents > 0) add(s(R.string.pdf_urgency, order.urgencyPercent) to MoneyInput.formatCents(order.urgencyCents))
            if (order.travelFeeCents > 0) add(s(R.string.pdf_travel_fee) to MoneyInput.formatCents(order.travelFeeCents))
            if (order.discountCents > 0) add(s(R.string.pdf_discount) to "- " + MoneyInput.formatCents(order.discountCents))
        }
        page.ensureSpace(rows.size * 16f + 40f)
        val labelX = PAGE_W - MARGIN - 330f
        rows.forEach { (label, value) ->
            drawText(page.canvas, label, labelX, page.y, 220f, paint(10f), Layout.Alignment.ALIGN_OPPOSITE)
            drawText(page.canvas, value, COL_TOTAL_X - 10f, page.y, COL_MONEY_W + 10f, paint(10f), Layout.Alignment.ALIGN_OPPOSITE)
            page.y += 16f
        }
        page.y += 4f
        page.canvas.drawRect(labelX, page.y, PAGE_W - MARGIN, page.y + 26f, Paint().apply { color = brand })
        val white = paint(13f, bold = true, color = Color.WHITE)
        drawText(page.canvas, s(R.string.pdf_total), labelX + 8f, page.y + 5f, 200f, white)
        drawText(page.canvas, MoneyInput.formatCents(order.totalCents), labelX, page.y + 5f, PAGE_W - MARGIN - labelX - 8f, white, Layout.Alignment.ALIGN_OPPOSITE)
        page.y += 40f
    }

    private fun drawNotes(page: PageCursor, data: OrderPdfData) {
        val blocks = listOfNotNull<Pair<String?, String>>(
            data.order.notes?.let { s(R.string.pdf_notes) to it },
            data.settings?.paymentTerms?.let { s(R.string.pdf_payment_terms) to it },
            data.settings?.footerNotes?.let { null to it }
        )
        blocks.forEach { (title, text) ->
            val body = paint(10f, color = if (title == null) mutedColor else textColor)
            page.ensureSpace(measure(text, CONTENT_W, body) + 20f)
            title?.let { page.y += drawText(page.canvas, it, MARGIN, page.y, CONTENT_W, paint(9f, bold = true, color = mutedColor)) + 2f }
            page.y += drawText(page.canvas, text, MARGIN, page.y, CONTENT_W, body) + 12f
        }
    }

    /** Photos in a grid of three, grouped by stage ("Antes", "Depois"). */
    private fun drawPhotos(page: PageCursor, data: OrderPdfData) {
        if (data.photos.isEmpty()) return
        val cellW = (CONTENT_W - 2 * PHOTO_GAP) / 3
        val cellH = cellW * 0.75f
        page.ensureSpace(cellH + 40f)
        page.y += drawText(page.canvas, s(R.string.pdf_photos), MARGIN, page.y, CONTENT_W, paint(9f, bold = true, color = mutedColor)) + 6f
        val border = Paint().apply { color = lineColor; style = Paint.Style.STROKE; strokeWidth = 0.8f }
        data.photos.groupBy({ it.first }, { it.second }).forEach { (stage, bitmaps) ->
            page.ensureSpace(cellH + 24f)
            page.y += drawText(page.canvas, s(stage.labelResId), MARGIN, page.y, CONTENT_W, paint(10f, bold = true)) + 4f
            bitmaps.chunked(3).forEach { row ->
                page.ensureSpace(cellH + PHOTO_GAP)
                row.forEachIndexed { index, bitmap ->
                    val left = MARGIN + index * (cellW + PHOTO_GAP)
                    val scale = minOf(cellW / bitmap.width, cellH / bitmap.height)
                    val w = bitmap.width * scale
                    val h = bitmap.height * scale
                    val x = left + (cellW - w) / 2
                    val y = page.y + (cellH - h) / 2
                    page.canvas.drawBitmap(bitmap, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    page.canvas.drawRect(left, page.y, left + cellW, page.y + cellH, border)
                }
                page.y += cellH + PHOTO_GAP
            }
        }
        page.y += 6f
    }

    private fun drawSignatures(page: PageCursor, data: OrderPdfData) {
        val signature = data.signature
        page.ensureSpace(if (signature != null) 130f else 80f)
        val half = (CONTENT_W - 40f) / 2
        page.y += if (signature != null) 70f else 40f
        signature?.let { bitmap ->
            val scale = minOf(half / bitmap.width, 64f / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val x = MARGIN + (half - w) / 2
            page.canvas.drawBitmap(bitmap, null, RectF(x, page.y - h - 2f, x + w, page.y - 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val line = Paint().apply { color = mutedColor; strokeWidth = 0.8f }
        listOf(MARGIN to R.string.pdf_signature_client, MARGIN + half + 40f to R.string.pdf_signature_technician).forEach { (x, label) ->
            page.canvas.drawLine(x, page.y, x + half, page.y, line)
            drawText(page.canvas, s(label), x, page.y + 4f, half, paint(9f, color = mutedColor), Layout.Alignment.ALIGN_CENTER)
        }
        val signedAt = data.order.signedAt
        if (signature != null && signedAt != null) {
            val who = data.order.signedBy ?: data.client.name
            drawText(
                page.canvas, s(R.string.pdf_signed_by, who, BrFormatters.formatDateTime(signedAt, data.zone)),
                MARGIN, page.y + 16f, half, paint(8f, color = mutedColor), Layout.Alignment.ALIGN_CENTER
            )
        }
        page.y += 34f
    }

    // endregion

    private fun measure(text: String, width: Float, paint: TextPaint): Float = layout(text, width, paint, Layout.Alignment.ALIGN_NORMAL).height.toFloat()

    private fun layout(text: String, width: Float, paint: TextPaint, alignment: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(alignment)
            .setIncludePad(false)
            .build()

    /** Draws wrapped text with its top at [y]; returns the height used. */
    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        width: Float,
        paint: TextPaint,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
    ): Float {
        val layout = layout(text, width, paint, alignment)
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return layout.height.toFloat()
    }

    /** Current page and vertical position; opens a new page when the content would not fit. */
    private inner class PageCursor(private val document: PdfDocument, private val brand: Int) {
        private var number = 0
        private lateinit var current: PdfDocument.Page
        var y = 0f
        val canvas: Canvas get() = current.canvas

        init {
            newPage()
        }

        private fun newPage() {
            number++
            current = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), number).create())
            current.canvas.drawRect(0f, 0f, PAGE_W, 6f, Paint().apply { color = brand })
            y = MARGIN
        }

        fun ensureSpace(height: Float, onNewPage: (PageCursor) -> Unit = {}) {
            if (y + height <= PAGE_H - FOOTER_H) return
            finishPage()
            newPage()
            onNewPage(this)
        }

        private fun finishPage() {
            drawText(
                canvas, s(R.string.pdf_footer, number), MARGIN, PAGE_H - 28f, CONTENT_W,
                paint(8f, color = mutedColor), Layout.Alignment.ALIGN_CENTER
            )
            document.finishPage(current)
        }

        fun finish() = finishPage()
    }

    private companion object {
        const val PAGE_W = 595f
        const val PAGE_H = 842f
        const val MARGIN = 40f
        const val FOOTER_H = 60f
        const val CONTENT_W = PAGE_W - 2 * MARGIN
        const val LOGO_MAX_W = 120f
        const val LOGO_MAX_H = 60f
        const val COL_MONEY_W = 80f
        const val COL_QTY_W = 60f
        const val COL_TOTAL_X = PAGE_W - MARGIN - COL_MONEY_W
        const val COL_UNIT_X = COL_TOTAL_X - COL_MONEY_W - 8f
        const val COL_QTY_X = COL_UNIT_X - COL_QTY_W - 8f
        const val COL_DESC_W = COL_QTY_X - MARGIN - 12f
        const val PHOTO_GAP = 8f
    }
}

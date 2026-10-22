package br.com.timasters.app.data.business.pdf

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.core.format.MoneyInput
import br.com.timasters.app.data.business.BusinessFiles
import br.com.timasters.app.data.business.ClientDao
import br.com.timasters.app.data.business.OrderKind
import br.com.timasters.app.data.business.OrderPhotoDao
import br.com.timasters.app.data.business.OrderRepository
import br.com.timasters.app.data.business.ServiceOrderEntity
import br.com.timasters.app.data.business.TicketDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the PDF of a quote / service order and the intents that send it. Every generated PDF
 * is also queued for upload, so the company keeps a copy in the cloud ("via da empresa") of what
 * the client received ("via do cliente").
 */
@Singleton
class OrderDocuments @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: OrderRepository,
    private val clientDao: ClientDao,
    private val ticketDao: TicketDao,
    private val photoDao: OrderPhotoDao,
    private val files: BusinessFiles
) {
    /** Returns the PDF file, or null if the record no longer exists. */
    suspend fun generatePdf(orderId: String): File? {
        val order = repository.orderNow(orderId) ?: return null
        val client = clientDao.get(order.clientId) ?: return null
        val ticket = order.ticketId?.let { ticketDao.get(it) }
        val company = repository.companyProfileNow(order.companyId)
        val kind = context.getString(if (order.orderKind == OrderKind.QUOTE) R.string.order_kind_quote else R.string.order_kind_service_order)
        val file = files.newOrderPdfFile(
            order.id,
            BusinessFiles.pdfFileName(kind, order.number?.toString() ?: order.id.take(8), company?.name.orEmpty())
        )
        val photos = photoDao.activeByOrder(order.id).sortedBy { it.photoStage.ordinal }
        withContext(Dispatchers.IO) {
            OrderPdfWriter(context).write(
                OrderPdfData(
                    order = order,
                    client = client,
                    company = company,
                    settings = repository.pricingSettingsNow(order.companyId),
                    logo = files.loadLogo(order.companyId),
                    ticketLabel = ticket?.let { t -> t.number?.let { "#$it · ${t.title}" } ?: t.title },
                    zone = ZoneId.systemDefault(),
                    // Small copies keep the PDF light enough for WhatsApp.
                    photos = photos.mapNotNull { photo ->
                        files.loadScaled(files.photoFile(photo.orderId, photo.id), PDF_PHOTO_SIDE)?.let { photo.photoStage to it }
                    },
                    signature = files.signatureFile(order)?.let { files.loadScaled(it, PDF_SIGNATURE_WIDTH) }
                ),
                file
            )
        }
        repository.markPdfGenerated(order.id)
        return file
    }

    fun contentUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** "Orçamento nº 12" / "Ordem de serviço nº 3" / "Orçamento" while it has no number yet. */
    fun documentName(order: ServiceOrderEntity): String {
        val kind = context.getString(if (order.orderKind == OrderKind.QUOTE) R.string.order_kind_quote else R.string.order_kind_service_order)
        return order.number?.let { context.getString(R.string.order_name_with_number, kind, it) } ?: kind
    }

    /** Short message that goes with the PDF (or alone, as text, on WhatsApp). */
    suspend fun shareMessage(order: ServiceOrderEntity, clientName: String): String {
        val company = repository.companyProfileNow(order.companyId)?.name ?: context.getString(R.string.app_name)
        val lines = mutableListOf(
            context.getString(R.string.share_greeting, clientName.substringBefore(' ')),
            context.getString(R.string.share_body, documentName(order), company),
            context.getString(R.string.share_total, MoneyInput.formatCents(order.totalCents))
        )
        order.validUntil?.let {
            lines += context.getString(R.string.share_valid_until, BrFormatters.formatDate(it, ZoneId.systemDefault()))
        }
        return lines.joinToString("\n")
    }

    /** PDF + message straight to WhatsApp (or WhatsApp Business); null if neither is installed. */
    fun whatsAppPdfIntent(file: File, message: String): Intent? {
        val uri = contentUri(file)
        return WHATSAPP_PACKAGES.firstNotNullOfOrNull { pkg ->
            pdfIntent(uri, message).setPackage(pkg).takeIf { it.resolveActivity(context.packageManager) != null }
        }
    }

    /** Opens the client's WhatsApp chat with the message typed in (text only). */
    fun whatsAppTextIntent(phone: String, message: String): Intent {
        val digits = phone.filter { it.isDigit() }
        val number = if (digits.length >= 12 && digits.startsWith("55")) digits else "55$digits"
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=${Uri.encode(message)}"))
    }

    /** Any app: e-mail, Drive, Telegram... */
    fun shareChooser(file: File, message: String, title: String): Intent =
        Intent.createChooser(pdfIntent(contentUri(file), message), title)

    fun viewIntent(file: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUri(file), PDF_MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun pdfIntent(uri: Uri, message: String): Intent = Intent(Intent.ACTION_SEND)
        .setType(PDF_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, message)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .also { it.clipData = ClipData.newRawUri(null, uri) }

    private companion object {
        const val PDF_MIME = "application/pdf"
        const val PDF_PHOTO_SIDE = 480
        const val PDF_SIGNATURE_WIDTH = 600
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}

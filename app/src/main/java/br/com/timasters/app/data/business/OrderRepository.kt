package br.com.timasters.app.data.business

import android.net.Uri
import androidx.room.withTransaction
import br.com.timasters.app.core.validation.DocumentValidator
import br.com.timasters.app.data.business.sync.BusinessSyncScheduler
import br.com.timasters.app.data.local.AppDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ServiceItemInput(
    val name: String,
    val description: String,
    val kind: ItemKind,
    val unit: String,
    val priceCents: Long,
    val active: Boolean
)

data class PricingSettingsInput(
    val hourlyRateCents: Long,
    val minimumChargeCents: Long,
    val travelFeeCents: Long,
    val urgencyPercent: Int,
    val quoteValidityDays: Int,
    val paymentTerms: String,
    val footerNotes: String
)

data class OrderInput(
    val kind: OrderKind,
    val clientId: String,
    val ticketId: String?,
    val status: OrderStatus,
    val items: List<OrderItem>,
    val laborMinutes: Int,
    val hourlyRateCents: Long,
    val minimumChargeCents: Long,
    val urgencyPercent: Int,
    val travelFeeCents: Long,
    val discountCents: Long,
    val notes: String,
    val validUntil: Long?
)

data class CompanyProfileInput(
    val name: String,
    val document: String,
    val phone: String,
    val email: String,
    val address: String,
    val city: String,
    val primaryColor: String?
)

/**
 * Catalog, prices, quotes / service orders and the company profile. Like [BusinessRepository],
 * everything is written on the phone first and uploaded by the sync.
 */
@Singleton
class OrderRepository @Inject constructor(
    private val database: AppDatabase,
    private val serviceItemDao: ServiceItemDao,
    private val pricingSettingsDao: PricingSettingsDao,
    private val serviceOrderDao: ServiceOrderDao,
    private val companyProfileDao: CompanyProfileDao,
    private val files: BusinessFiles,
    private val syncScheduler: BusinessSyncScheduler
) {
    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString()

    // Catalog

    fun catalog(companyId: String): Flow<List<ServiceItemEntity>> = serviceItemDao.observeAll(companyId)

    suspend fun catalogItem(id: String): ServiceItemEntity? = serviceItemDao.get(id)

    suspend fun saveCatalogItem(companyId: String, id: String?, input: ServiceItemInput): String {
        val existing = id?.let { serviceItemDao.get(it) }
        val time = now()
        val entity = ServiceItemEntity(
            id = existing?.id ?: newId(),
            companyId = existing?.companyId ?: companyId,
            name = input.name.trim(),
            description = input.description.trim().ifEmpty { null },
            kind = input.kind.serverId,
            unit = input.unit.trim().ifEmpty { "un" }.take(10),
            priceCents = input.priceCents,
            active = input.active,
            createdAt = existing?.createdAt ?: time,
            updatedAt = time,
            serverUpdatedAt = existing?.serverUpdatedAt,
            isDirty = true
        )
        serviceItemDao.upsert(entity)
        syncScheduler.requestSync()
        return entity.id
    }

    suspend fun deleteCatalogItem(id: String) {
        val existing = serviceItemDao.get(id) ?: return
        val time = now()
        serviceItemDao.upsert(existing.copy(deletedAt = time, updatedAt = time, isDirty = true))
        syncScheduler.requestSync()
    }

    // Pricing settings

    fun pricingSettings(companyId: String): Flow<PricingSettingsEntity?> = pricingSettingsDao.observe(companyId)

    suspend fun pricingSettingsNow(companyId: String): PricingSettingsEntity? = pricingSettingsDao.get(companyId)

    suspend fun savePricingSettings(companyId: String, input: PricingSettingsInput) {
        val existing = pricingSettingsDao.get(companyId)
        val time = now()
        pricingSettingsDao.upsert(
            PricingSettingsEntity(
                id = companyId,
                companyId = companyId,
                hourlyRateCents = input.hourlyRateCents,
                minimumChargeCents = input.minimumChargeCents,
                travelFeeCents = input.travelFeeCents,
                urgencyPercent = input.urgencyPercent.coerceIn(0, 300),
                quoteValidityDays = input.quoteValidityDays.coerceIn(1, 365),
                paymentTerms = input.paymentTerms.trim().ifEmpty { null },
                footerNotes = input.footerNotes.trim().ifEmpty { null },
                createdAt = existing?.createdAt ?: time,
                updatedAt = time,
                serverUpdatedAt = existing?.serverUpdatedAt,
                isDirty = true
            )
        )
        syncScheduler.requestSync()
    }

    // Quotes and service orders

    fun orders(companyId: String): Flow<List<ServiceOrderWithClient>> = serviceOrderDao.observeAll(companyId)
    fun ordersOfClient(clientId: String): Flow<List<ServiceOrderWithClient>> = serviceOrderDao.observeByClient(clientId)
    fun ordersOfTicket(ticketId: String): Flow<List<ServiceOrderWithClient>> = serviceOrderDao.observeByTicket(ticketId)
    fun order(id: String): Flow<ServiceOrderWithClient?> = serviceOrderDao.observe(id)
    suspend fun orderNow(id: String): ServiceOrderEntity? = serviceOrderDao.get(id)

    suspend fun saveOrder(companyId: String, id: String?, input: OrderInput): String {
        val existing = id?.let { serviceOrderDao.get(it) }
        val prices = OrderCalculator.calculate(
            PricingInput(
                items = input.items,
                laborMinutes = input.laborMinutes,
                hourlyRateCents = input.hourlyRateCents,
                minimumChargeCents = input.minimumChargeCents,
                urgencyPercent = input.urgencyPercent,
                travelFeeCents = input.travelFeeCents,
                discountCents = input.discountCents
            )
        )
        val time = now()
        val entity = ServiceOrderEntity(
            id = existing?.id ?: newId(),
            companyId = existing?.companyId ?: companyId,
            clientId = input.clientId,
            ticketId = input.ticketId,
            sourceQuoteId = existing?.sourceQuoteId,
            kind = existing?.kind ?: input.kind.serverId,
            number = existing?.number,
            status = input.status.serverId,
            itemsJson = OrderItem.encodeList(input.items),
            itemsCents = prices.itemsCents,
            laborMinutes = input.laborMinutes,
            hourlyRateCents = input.hourlyRateCents,
            laborCents = prices.laborCents,
            urgencyPercent = input.urgencyPercent,
            urgencyCents = prices.urgencyCents,
            travelFeeCents = prices.travelFeeCents,
            discountCents = prices.discountCents,
            totalCents = prices.totalCents,
            notes = input.notes.trim().ifEmpty { null },
            validUntil = if (input.kind == OrderKind.QUOTE) input.validUntil else null,
            pdfPath = existing?.pdfPath,
            pdfPendingUpload = existing?.pdfPendingUpload ?: false,
            sentAt = existing?.sentAt,
            createdAt = existing?.createdAt ?: time,
            updatedAt = time,
            serverUpdatedAt = existing?.serverUpdatedAt,
            isDirty = true
        )
        serviceOrderDao.upsert(entity)
        syncScheduler.requestSync()
        return entity.id
    }

    suspend fun setOrderStatus(id: String, status: OrderStatus) {
        val existing = serviceOrderDao.get(id) ?: return
        if (status.kind != existing.orderKind) return
        serviceOrderDao.upsert(existing.copy(status = status.serverId, updatedAt = now(), isDirty = true))
        syncScheduler.requestSync()
    }

    /**
     * Creates the service order of an approved quote (same items and prices) and marks the
     * quote as approved. If one was already created, returns it instead of making a copy.
     */
    suspend fun convertQuoteToOrder(quoteId: String): String? {
        val quote = serviceOrderDao.get(quoteId)?.takeIf { it.orderKind == OrderKind.QUOTE } ?: return null
        serviceOrderDao.fromQuote(quoteId).firstOrNull()?.let { return it.id }
        val time = now()
        val order = quote.copy(
            id = newId(),
            sourceQuoteId = quote.id,
            kind = OrderKind.SERVICE_ORDER.serverId,
            number = null,
            status = OrderStatus.OPEN.serverId,
            validUntil = null,
            pdfPath = null,
            pdfPendingUpload = false,
            sentAt = null,
            createdAt = time,
            updatedAt = time,
            serverUpdatedAt = null,
            isDirty = true
        )
        database.withTransaction {
            serviceOrderDao.upsert(quote.copy(status = OrderStatus.APPROVED.serverId, updatedAt = time, isDirty = true))
            serviceOrderDao.upsert(order)
        }
        syncScheduler.requestSync()
        return order.id
    }

    /** A new PDF exists on the phone: upload it as the company copy at the next sync. */
    suspend fun markPdfGenerated(id: String) {
        val existing = serviceOrderDao.get(id) ?: return
        serviceOrderDao.upsert(existing.copy(pdfPendingUpload = true, updatedAt = now()))
        syncScheduler.requestSync()
    }

    /** Sending a draft quote to the client moves it to "sent". */
    suspend fun markSent(id: String) {
        val existing = serviceOrderDao.get(id) ?: return
        val status = if (existing.status == OrderStatus.DRAFT.serverId) OrderStatus.SENT.serverId else existing.status
        serviceOrderDao.upsert(existing.copy(status = status, sentAt = now(), updatedAt = now(), isDirty = true))
        syncScheduler.requestSync()
    }

    suspend fun deleteOrder(id: String) {
        val existing = serviceOrderDao.get(id) ?: return
        val time = now()
        serviceOrderDao.upsert(existing.copy(deletedAt = time, updatedAt = time, isDirty = true))
        files.deleteOrderPdf(id)
        syncScheduler.requestSync()
    }

    // Company profile

    fun companyProfile(companyId: String): Flow<CompanyProfileEntity?> = companyProfileDao.observe(companyId)

    suspend fun companyProfileNow(companyId: String): CompanyProfileEntity? = companyProfileDao.get(companyId)

    suspend fun saveCompanyProfile(companyId: String, input: CompanyProfileInput) {
        val existing = companyProfileDao.get(companyId)
        val document = DocumentValidator.normalize(input.document).ifEmpty { null }
        companyProfileDao.upsert(
            CompanyProfileEntity(
                id = companyId,
                name = input.name.trim(),
                document = document,
                phone = input.phone.filter { it.isDigit() }.ifEmpty { null },
                email = input.email.trim().lowercase().ifEmpty { null },
                address = input.address.trim().ifEmpty { null },
                city = input.city.trim().ifEmpty { null },
                primaryColor = input.primaryColor,
                logoPath = existing?.logoPath,
                logoPendingUpload = existing?.logoPendingUpload ?: false,
                updatedAt = now(),
                serverUpdatedAt = existing?.serverUpdatedAt,
                isDirty = true
            )
        )
        syncScheduler.requestSync()
    }

    /** Returns false when the picked file is not an image. */
    suspend fun setLogo(companyId: String, source: Uri): Boolean {
        val existing = companyProfileDao.get(companyId) ?: return false
        if (!files.importLogo(companyId, source)) return false
        companyProfileDao.upsert(existing.copy(logoPendingUpload = true, updatedAt = now()))
        syncScheduler.requestSync()
        return true
    }

    suspend fun removeLogo(companyId: String) {
        val existing = companyProfileDao.get(companyId) ?: return
        files.deleteLogo(companyId)
        companyProfileDao.upsert(existing.copy(logoPendingUpload = true, updatedAt = now()))
        syncScheduler.requestSync()
    }
}

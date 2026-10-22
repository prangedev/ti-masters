package br.com.timasters.app.data.business

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Business part 2 tables. Same sync fields as BusinessEntities (id, updatedAt, serverUpdatedAt,
 * isDirty, deletedAt). Money is always stored in cents.
 */

@Entity(tableName = "service_items", indices = [Index("companyId"), Index("isDirty")])
data class ServiceItemEntity(
    @PrimaryKey val id: String,
    val companyId: String,
    val name: String,
    val description: String? = null,
    val kind: String = ItemKind.SERVICE.serverId,
    val unit: String = "un",
    val priceCents: Long = 0,
    val active: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val serverUpdatedAt: String? = null,
    val isDirty: Boolean = true,
    val deletedAt: Long? = null
)

/** One row per company; [id] is the company id. */
@Entity(tableName = "pricing_settings", indices = [Index("companyId"), Index("isDirty")])
data class PricingSettingsEntity(
    @PrimaryKey val id: String,
    val companyId: String,
    val hourlyRateCents: Long = 0,
    val minimumChargeCents: Long = 0,
    val travelFeeCents: Long = 0,
    val urgencyPercent: Int = 0,
    val quoteValidityDays: Int = 15,
    val paymentTerms: String? = null,
    val footerNotes: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val serverUpdatedAt: String? = null,
    val isDirty: Boolean = true,
    val deletedAt: Long? = null
)

/** A quote ("orcamento") or a service order ("os"). Prices are a snapshot taken when saved. */
@Entity(
    tableName = "service_orders",
    indices = [Index("companyId"), Index("clientId"), Index("ticketId"), Index("isDirty")]
)
data class ServiceOrderEntity(
    @PrimaryKey val id: String,
    val companyId: String,
    val clientId: String,
    val ticketId: String? = null,
    val sourceQuoteId: String? = null,
    val kind: String,
    /** Assigned by the server on the first upload; null while offline. */
    val number: Int? = null,
    val status: String,
    /** JSON array of [OrderItem]. */
    val itemsJson: String = "[]",
    val itemsCents: Long = 0,
    val laborMinutes: Int = 0,
    val hourlyRateCents: Long = 0,
    val laborCents: Long = 0,
    val urgencyPercent: Int = 0,
    val urgencyCents: Long = 0,
    val travelFeeCents: Long = 0,
    val discountCents: Long = 0,
    val totalCents: Long = 0,
    val notes: String? = null,
    val validUntil: Long? = null,
    /** Storage path of the company's copy of the PDF ("<company>/<id>.pdf"). */
    val pdfPath: String? = null,
    /** Local only: a PDF was generated here and still has to be uploaded. */
    val pdfPendingUpload: Boolean = false,
    val sentAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val serverUpdatedAt: String? = null,
    val isDirty: Boolean = true,
    val deletedAt: Long? = null
) {
    val orderKind: OrderKind get() = OrderKind.from(kind)
    val items: List<OrderItem> get() = OrderItem.decodeList(itemsJson)
}

/**
 * Local copy of the company row (name, contacts, brand). Edited here and uploaded by the sync;
 * the logo file is kept in the app's private storage (see BrandingFiles).
 */
@Entity(tableName = "company_profile")
data class CompanyProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val document: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val city: String? = null,
    /** "#RRGGBB" or null for the TI Masters colors. */
    val primaryColor: String? = null,
    /** Storage path of the logo on the server; changes on every new logo. */
    val logoPath: String? = null,
    /** Local only: a new logo was chosen here and still has to be uploaded. */
    val logoPendingUpload: Boolean = false,
    val updatedAt: Long,
    val serverUpdatedAt: String? = null,
    val isDirty: Boolean = false
)

/** One line of a quote or service order. */
@Serializable
data class OrderItem(
    @SerialName("catalog_id") val catalogId: String? = null,
    val description: String,
    val unit: String = "un",
    val quantity: Double = 1.0,
    @SerialName("unit_price_cents") val unitPriceCents: Long = 0
) {
    val totalCents: Long get() = Math.round(quantity * unitPriceCents)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decodeList(value: String): List<OrderItem> =
            runCatching { json.decodeFromString<List<OrderItem>>(value) }.getOrDefault(emptyList())

        fun encodeList(items: List<OrderItem>): String = json.encodeToString(items)
    }
}

data class ServiceOrderWithClient(
    @Embedded val order: ServiceOrderEntity,
    val clientName: String,
    val clientPhone: String?,
    val ticketNumber: Int?,
    val ticketTitle: String?
)

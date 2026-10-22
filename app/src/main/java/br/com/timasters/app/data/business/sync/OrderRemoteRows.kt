package br.com.timasters.app.data.business.sync

import br.com.timasters.app.core.time.IsoTime
import br.com.timasters.app.data.business.CompanyProfileEntity
import br.com.timasters.app.data.business.PricingSettingsEntity
import br.com.timasters.app.data.business.ServiceItemEntity
import br.com.timasters.app.data.business.ServiceOrderEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/* Server rows of Business part 2; same Push/Row convention as BusinessRemoteRows. */

@Serializable
data class ServiceItemPush(
    val id: String,
    @SerialName("company_id") val companyId: String,
    val name: String,
    val description: String?,
    val kind: String,
    val unit: String,
    @SerialName("price_cents") val priceCents: Long,
    val active: Boolean,
    @SerialName("deleted_at") val deletedAt: String?
)

@Serializable
data class ServiceItemRow(
    val id: String,
    @SerialName("company_id") val companyId: String,
    val name: String,
    val description: String? = null,
    val kind: String,
    val unit: String,
    @SerialName("price_cents") val priceCents: Long,
    val active: Boolean = true,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class PricingSettingsPush(
    val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("hourly_rate_cents") val hourlyRateCents: Long,
    @SerialName("minimum_charge_cents") val minimumChargeCents: Long,
    @SerialName("travel_fee_cents") val travelFeeCents: Long,
    @SerialName("urgency_percent") val urgencyPercent: Int,
    @SerialName("quote_validity_days") val quoteValidityDays: Int,
    @SerialName("payment_terms") val paymentTerms: String?,
    @SerialName("footer_notes") val footerNotes: String?,
    @SerialName("deleted_at") val deletedAt: String?
)

@Serializable
data class PricingSettingsRow(
    val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("hourly_rate_cents") val hourlyRateCents: Long,
    @SerialName("minimum_charge_cents") val minimumChargeCents: Long,
    @SerialName("travel_fee_cents") val travelFeeCents: Long,
    @SerialName("urgency_percent") val urgencyPercent: Int,
    @SerialName("quote_validity_days") val quoteValidityDays: Int,
    @SerialName("payment_terms") val paymentTerms: String? = null,
    @SerialName("footer_notes") val footerNotes: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class ServiceOrderPush(
    val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("ticket_id") val ticketId: String?,
    @SerialName("source_quote_id") val sourceQuoteId: String?,
    val kind: String,
    val status: String,
    val items: JsonArray,
    @SerialName("items_cents") val itemsCents: Long,
    @SerialName("labor_minutes") val laborMinutes: Int,
    @SerialName("hourly_rate_cents") val hourlyRateCents: Long,
    @SerialName("labor_cents") val laborCents: Long,
    @SerialName("urgency_percent") val urgencyPercent: Int,
    @SerialName("urgency_cents") val urgencyCents: Long,
    @SerialName("travel_fee_cents") val travelFeeCents: Long,
    @SerialName("discount_cents") val discountCents: Long,
    @SerialName("total_cents") val totalCents: Long,
    val notes: String?,
    @SerialName("valid_until") val validUntil: String?,
    @SerialName("pdf_path") val pdfPath: String?,
    @SerialName("sent_at") val sentAt: String?,
    @SerialName("deleted_at") val deletedAt: String?
)

@Serializable
data class ServiceOrderRow(
    val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("ticket_id") val ticketId: String? = null,
    @SerialName("source_quote_id") val sourceQuoteId: String? = null,
    val kind: String,
    val number: Int? = null,
    val status: String,
    val items: JsonArray = JsonArray(emptyList()),
    @SerialName("items_cents") val itemsCents: Long = 0,
    @SerialName("labor_minutes") val laborMinutes: Int = 0,
    @SerialName("hourly_rate_cents") val hourlyRateCents: Long = 0,
    @SerialName("labor_cents") val laborCents: Long = 0,
    @SerialName("urgency_percent") val urgencyPercent: Int = 0,
    @SerialName("urgency_cents") val urgencyCents: Long = 0,
    @SerialName("travel_fee_cents") val travelFeeCents: Long = 0,
    @SerialName("discount_cents") val discountCents: Long = 0,
    @SerialName("total_cents") val totalCents: Long = 0,
    val notes: String? = null,
    @SerialName("valid_until") val validUntil: String? = null,
    @SerialName("pdf_path") val pdfPath: String? = null,
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

/** Columns of the companies table that the app edits (owners and admins). */
@Serializable
data class CompanyPush(
    val name: String,
    @SerialName("cnpj_cpf") val document: String?,
    val phone: String?,
    val email: String?,
    val address: String?,
    val city: String?,
    @SerialName("primary_color") val primaryColor: String?,
    @SerialName("logo_path") val logoPath: String?
)

@Serializable
data class CompanyRow(
    val id: String,
    val name: String,
    @SerialName("cnpj_cpf") val document: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val city: String? = null,
    @SerialName("primary_color") val primaryColor: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("updated_at") val updatedAt: String
)

private val lenientJson = Json { ignoreUnknownKeys = true }

private fun Long?.iso(): String? = this?.let(IsoTime::format)
private fun String.millis(): Long = IsoTime.parse(this) ?: 0L

fun ServiceItemEntity.toPush() = ServiceItemPush(id, companyId, name, description, kind, unit, priceCents, active, deletedAt.iso())

fun ServiceItemRow.toEntity() = ServiceItemEntity(
    id = id, companyId = companyId, name = name, description = description, kind = kind, unit = unit,
    priceCents = priceCents, active = active, createdAt = createdAt.millis(), updatedAt = updatedAt.millis(),
    serverUpdatedAt = updatedAt, isDirty = false, deletedAt = IsoTime.parse(deletedAt)
)

fun PricingSettingsEntity.toPush() = PricingSettingsPush(
    id, companyId, hourlyRateCents, minimumChargeCents, travelFeeCents, urgencyPercent, quoteValidityDays,
    paymentTerms, footerNotes, deletedAt.iso()
)

fun PricingSettingsRow.toEntity() = PricingSettingsEntity(
    id = id, companyId = companyId, hourlyRateCents = hourlyRateCents, minimumChargeCents = minimumChargeCents,
    travelFeeCents = travelFeeCents, urgencyPercent = urgencyPercent, quoteValidityDays = quoteValidityDays,
    paymentTerms = paymentTerms, footerNotes = footerNotes, createdAt = createdAt.millis(),
    updatedAt = updatedAt.millis(), serverUpdatedAt = updatedAt, isDirty = false, deletedAt = IsoTime.parse(deletedAt)
)

fun ServiceOrderEntity.toPush() = ServiceOrderPush(
    id = id, companyId = companyId, clientId = clientId, ticketId = ticketId, sourceQuoteId = sourceQuoteId,
    kind = kind, status = status,
    items = runCatching { lenientJson.decodeFromString<JsonArray>(itemsJson) }.getOrDefault(JsonArray(emptyList())),
    itemsCents = itemsCents, laborMinutes = laborMinutes, hourlyRateCents = hourlyRateCents, laborCents = laborCents,
    urgencyPercent = urgencyPercent, urgencyCents = urgencyCents, travelFeeCents = travelFeeCents,
    discountCents = discountCents, totalCents = totalCents, notes = notes, validUntil = validUntil.iso(),
    pdfPath = pdfPath, sentAt = sentAt.iso(), deletedAt = deletedAt.iso()
)

/** [pendingPdf] keeps the local "PDF still to upload" flag, which the server knows nothing about. */
fun ServiceOrderRow.toEntity(pendingPdf: Boolean = false) = ServiceOrderEntity(
    id = id, companyId = companyId, clientId = clientId, ticketId = ticketId, sourceQuoteId = sourceQuoteId,
    kind = kind, number = number, status = status, itemsJson = items.toString(), itemsCents = itemsCents,
    laborMinutes = laborMinutes, hourlyRateCents = hourlyRateCents, laborCents = laborCents,
    urgencyPercent = urgencyPercent, urgencyCents = urgencyCents, travelFeeCents = travelFeeCents,
    discountCents = discountCents, totalCents = totalCents, notes = notes, validUntil = IsoTime.parse(validUntil),
    pdfPath = pdfPath, pdfPendingUpload = pendingPdf, sentAt = IsoTime.parse(sentAt),
    createdAt = createdAt.millis(), updatedAt = updatedAt.millis(), serverUpdatedAt = updatedAt,
    isDirty = false, deletedAt = IsoTime.parse(deletedAt)
)

fun CompanyProfileEntity.toPush() = CompanyPush(name, document, phone, email, address, city, primaryColor, logoPath)

fun CompanyRow.toEntity() = CompanyProfileEntity(
    id = id, name = name, document = document, phone = phone, email = email, address = address, city = city,
    primaryColor = primaryColor, logoPath = logoPath, logoPendingUpload = false,
    updatedAt = updatedAt.millis(), serverUpdatedAt = updatedAt, isDirty = false
)

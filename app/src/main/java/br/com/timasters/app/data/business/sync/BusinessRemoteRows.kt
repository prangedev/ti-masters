package br.com.timasters.app.data.business.sync

import br.com.timasters.app.core.time.IsoTime
import br.com.timasters.app.data.business.AppointmentEntity
import br.com.timasters.app.data.business.ClientEntity
import br.com.timasters.app.data.business.TicketEntity
import br.com.timasters.app.data.business.TimeEntryEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Server rows. "*Push" classes contain only the columns the app may write (see the column grants
 * in the migration); "*Row" classes are what the app reads back, including server-owned values.
 * Push fields have no defaults so nulls are always sent (e.g. clearing a phone number).
 */

@Serializable
data class ClientPush(
    val id: String,
    @SerialName("company_id") val companyId: String,
    val name: String,
    @SerialName("doc_type") val docType: String?,
    @SerialName("doc_number") val docNumber: String?,
    val email: String?,
    val phone: String?,
    val address: String?,
    val city: String?,
    val notes: String?,
    @SerialName("deleted_at") val deletedAt: String?
)

@Serializable
data class ClientRow(
    val id: String,
    @SerialName("company_id") val companyId: String,
    val name: String,
    @SerialName("doc_type") val docType: String? = null,
    @SerialName("doc_number") val docNumber: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val city: String? = null,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class TicketPush(
    val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("client_id") val clientId: String,
    val title: String,
    val description: String?,
    val priority: String,
    val status: String,
    @SerialName("due_at") val dueAt: String?,
    @SerialName("closed_at") val closedAt: String?,
    @SerialName("deleted_at") val deletedAt: String?
)

@Serializable
data class TicketRow(
    val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("client_id") val clientId: String,
    val number: Int? = null,
    val title: String,
    val description: String? = null,
    val priority: String,
    val status: String,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class AppointmentPush(
    val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("ticket_id") val ticketId: String?,
    val kind: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    val address: String?,
    val notes: String?,
    val status: String,
    @SerialName("reminder_minutes") val reminderMinutes: Int?,
    @SerialName("deleted_at") val deletedAt: String?
)

@Serializable
data class AppointmentRow(
    val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("ticket_id") val ticketId: String? = null,
    val kind: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    val address: String? = null,
    val notes: String? = null,
    val status: String,
    @SerialName("reminder_minutes") val reminderMinutes: Int? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class TimeEntryPush(
    val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("ticket_id") val ticketId: String?,
    @SerialName("user_id") val userId: String?,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String?,
    val note: String?,
    @SerialName("deleted_at") val deletedAt: String?
)

@Serializable
data class TimeEntryRow(
    val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("ticket_id") val ticketId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

private fun Long?.iso(): String? = this?.let(IsoTime::format)
private fun String.millis(): Long = IsoTime.parse(this) ?: 0L

fun ClientEntity.toPush() = ClientPush(id, companyId, name, docType, docNumber, email, phone, address, city, notes, deletedAt.iso())

fun ClientRow.toEntity() = ClientEntity(
    id = id, companyId = companyId, name = name, docType = docType, docNumber = docNumber, email = email,
    phone = phone, address = address, city = city, notes = notes,
    createdAt = createdAt.millis(), updatedAt = updatedAt.millis(), serverUpdatedAt = updatedAt,
    isDirty = false, deletedAt = IsoTime.parse(deletedAt)
)

fun TicketEntity.toPush() = TicketPush(
    id, companyId, clientId, title, description, priority, status, dueAt.iso(), closedAt.iso(), deletedAt.iso()
)

fun TicketRow.toEntity() = TicketEntity(
    id = id, companyId = companyId, clientId = clientId, number = number, title = title, description = description,
    priority = priority, status = status, dueAt = IsoTime.parse(dueAt), closedAt = IsoTime.parse(closedAt),
    createdAt = createdAt.millis(), updatedAt = updatedAt.millis(), serverUpdatedAt = updatedAt,
    isDirty = false, deletedAt = IsoTime.parse(deletedAt)
)

fun AppointmentEntity.toPush() = AppointmentPush(
    id, companyId, clientId, ticketId, kind, IsoTime.format(startsAt), IsoTime.format(endsAt),
    address, notes, status, reminderMinutes, deletedAt.iso()
)

fun AppointmentRow.toEntity() = AppointmentEntity(
    id = id, companyId = companyId, clientId = clientId, ticketId = ticketId, kind = kind,
    startsAt = startsAt.millis(), endsAt = endsAt.millis(), address = address, notes = notes, status = status,
    reminderMinutes = reminderMinutes, createdAt = createdAt.millis(), updatedAt = updatedAt.millis(),
    serverUpdatedAt = updatedAt, isDirty = false, deletedAt = IsoTime.parse(deletedAt)
)

fun TimeEntryEntity.toPush() = TimeEntryPush(
    id, companyId, clientId, ticketId, userId, IsoTime.format(startedAt), endedAt.iso(), note, deletedAt.iso()
)

fun TimeEntryRow.toEntity() = TimeEntryEntity(
    id = id, companyId = companyId, clientId = clientId, ticketId = ticketId, userId = userId,
    startedAt = startedAt.millis(), endedAt = IsoTime.parse(endedAt), note = note,
    createdAt = createdAt.millis(), updatedAt = updatedAt.millis(), serverUpdatedAt = updatedAt,
    isDirty = false, deletedAt = IsoTime.parse(deletedAt)
)

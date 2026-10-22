package br.com.timasters.app.data.business

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Local copies of the Business tables. Every row carries the same sync fields:
 * - id: UUID created on the phone, so records can be created offline;
 * - updatedAt: last local edit (epoch millis), used to detect edits made during a sync;
 * - serverUpdatedAt: the server's updated_at of the version we have (null = never uploaded);
 * - isDirty: changed here and not yet uploaded;
 * - deletedAt: soft delete, uploaded like any other change so other devices remove it too.
 * Enum-like columns keep the server text values (see BusinessTypes).
 */

@Entity(tableName = "clients", indices = [Index("companyId"), Index("isDirty")])
data class ClientEntity(
    @PrimaryKey val id: String,
    val companyId: String,
    val name: String,
    val docType: String? = null,
    val docNumber: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val city: String? = null,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val serverUpdatedAt: String? = null,
    val isDirty: Boolean = true,
    val deletedAt: Long? = null
)

@Entity(tableName = "tickets", indices = [Index("companyId"), Index("clientId"), Index("isDirty")])
data class TicketEntity(
    @PrimaryKey val id: String,
    val companyId: String,
    val clientId: String,
    /** Assigned by the server on the first upload; null while offline. */
    val number: Int? = null,
    val title: String,
    val description: String? = null,
    val priority: String = TicketPriority.MEDIUM.serverId,
    val status: String = TicketStatus.OPEN.serverId,
    val dueAt: Long? = null,
    val closedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val serverUpdatedAt: String? = null,
    val isDirty: Boolean = true,
    val deletedAt: Long? = null
)

@Entity(tableName = "appointments", indices = [Index("companyId"), Index("clientId"), Index("ticketId"), Index("isDirty")])
data class AppointmentEntity(
    @PrimaryKey val id: String,
    val companyId: String,
    val clientId: String,
    val ticketId: String? = null,
    val kind: String = AppointmentKind.ON_SITE.serverId,
    val startsAt: Long,
    val endsAt: Long,
    val address: String? = null,
    val notes: String? = null,
    val status: String = AppointmentStatus.SCHEDULED.serverId,
    val reminderMinutes: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val serverUpdatedAt: String? = null,
    val isDirty: Boolean = true,
    val deletedAt: Long? = null
)

@Entity(tableName = "time_entries", indices = [Index("companyId"), Index("clientId"), Index("ticketId"), Index("isDirty")])
data class TimeEntryEntity(
    @PrimaryKey val id: String,
    val companyId: String,
    val clientId: String,
    val ticketId: String? = null,
    val userId: String? = null,
    val startedAt: Long,
    /** Null while the timer is running. */
    val endedAt: Long? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val serverUpdatedAt: String? = null,
    val isDirty: Boolean = true,
    val deletedAt: Long? = null
) {
    fun durationMillis(now: Long = System.currentTimeMillis()): Long = ((endedAt ?: now) - startedAt).coerceAtLeast(0)
}

/** Ticket plus the name of its client, for lists. */
data class TicketWithClient(
    @Embedded val ticket: TicketEntity,
    val clientName: String
)

data class AppointmentWithClient(
    @Embedded val appointment: AppointmentEntity,
    val clientName: String,
    val ticketNumber: Int?,
    val ticketTitle: String?
)

data class TimeEntryWithClient(
    @Embedded val entry: TimeEntryEntity,
    val clientName: String,
    val ticketNumber: Int?,
    val ticketTitle: String?
)

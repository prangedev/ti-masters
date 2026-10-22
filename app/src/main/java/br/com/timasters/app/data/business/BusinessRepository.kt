package br.com.timasters.app.data.business

import androidx.room.withTransaction
import br.com.timasters.app.core.validation.DocumentType
import br.com.timasters.app.core.validation.DocumentValidator
import br.com.timasters.app.data.auth.AuthRepository
import br.com.timasters.app.data.business.reminders.AppointmentReminderScheduler
import br.com.timasters.app.data.business.sync.BusinessSyncScheduler
import br.com.timasters.app.data.local.AppDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ClientInput(
    val name: String,
    val document: String,
    val email: String,
    val phone: String,
    val address: String,
    val city: String,
    val notes: String
)

data class TicketInput(
    val clientId: String,
    val title: String,
    val description: String,
    val priority: TicketPriority,
    val status: TicketStatus,
    val dueAt: Long?
)

data class AppointmentInput(
    val clientId: String,
    val ticketId: String?,
    val kind: AppointmentKind,
    val startsAt: Long,
    val endsAt: Long,
    val address: String,
    val notes: String,
    val reminder: ReminderOption
)

data class TimeEntryInput(
    val clientId: String,
    val ticketId: String?,
    val startedAt: Long,
    val endedAt: Long,
    val note: String
)

/**
 * All Business reads and writes. Writes go to the phone's database first (works offline), are
 * flagged as pending and then uploaded by [BusinessSyncScheduler] when there is a connection.
 */
@Singleton
class BusinessRepository @Inject constructor(
    private val database: AppDatabase,
    private val clientDao: ClientDao,
    private val ticketDao: TicketDao,
    private val appointmentDao: AppointmentDao,
    private val timeEntryDao: TimeEntryDao,
    private val serviceOrderDao: ServiceOrderDao,
    private val authRepository: AuthRepository,
    private val syncScheduler: BusinessSyncScheduler,
    private val reminders: AppointmentReminderScheduler
) {
    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString()

    // Clients

    fun clients(companyId: String): Flow<List<ClientEntity>> = clientDao.observeAll(companyId)
    fun client(id: String): Flow<ClientEntity?> = clientDao.observe(id)

    suspend fun saveClient(companyId: String, id: String?, input: ClientInput): String {
        val existing = id?.let { clientDao.get(it) }
        val document = DocumentValidator.normalize(input.document).ifEmpty { null }
        val time = now()
        val entity = ClientEntity(
            id = existing?.id ?: newId(),
            companyId = existing?.companyId ?: companyId,
            name = input.name.trim(),
            docType = document?.let {
                when (DocumentValidator.detectType(it)) {
                    DocumentType.CPF -> "cpf"
                    DocumentType.CNPJ -> "cnpj"
                    null -> null
                }
            },
            docNumber = document,
            email = input.email.trim().lowercase().ifEmpty { null },
            phone = input.phone.filter { it.isDigit() }.ifEmpty { null },
            address = input.address.trim().ifEmpty { null },
            city = input.city.trim().ifEmpty { null },
            notes = input.notes.trim().ifEmpty { null },
            createdAt = existing?.createdAt ?: time,
            updatedAt = time,
            serverUpdatedAt = existing?.serverUpdatedAt,
            isDirty = true
        )
        clientDao.upsert(entity)
        syncScheduler.requestSync()
        return entity.id
    }

    /** Removes the client and everything linked to it, on every device after the next sync. */
    suspend fun deleteClient(id: String) {
        val time = now()
        database.withTransaction {
            clientDao.get(id)?.let { clientDao.upsert(it.copy(deletedAt = time, updatedAt = time, isDirty = true)) }
            ticketDao.activeByClient(id).forEach {
                ticketDao.upsert(it.copy(deletedAt = time, updatedAt = time, isDirty = true))
            }
            appointmentDao.activeByClient(id).forEach {
                appointmentDao.upsert(it.copy(deletedAt = time, updatedAt = time, isDirty = true))
                reminders.cancel(it.id)
            }
            timeEntryDao.activeByClient(id).forEach {
                timeEntryDao.upsert(it.copy(deletedAt = time, updatedAt = time, isDirty = true))
            }
            serviceOrderDao.activeByClient(id).forEach {
                serviceOrderDao.upsert(it.copy(deletedAt = time, updatedAt = time, isDirty = true))
            }
        }
        syncScheduler.requestSync()
    }

    // Tickets

    fun tickets(companyId: String): Flow<List<TicketWithClient>> = ticketDao.observeAll(companyId)
    fun ticketsOfClient(clientId: String): Flow<List<TicketWithClient>> = ticketDao.observeByClient(clientId)
    fun ticket(id: String): Flow<TicketWithClient?> = ticketDao.observe(id)
    suspend fun ticketNow(id: String): TicketEntity? = ticketDao.get(id)

    /** Minutes recorded on a ticket (a running timer counts up to now). */
    suspend fun ticketMinutes(ticketId: String): Int =
        (timeEntryDao.activeByTicket(ticketId).sumOf { it.durationMillis() } / 60_000).toInt()

    suspend fun saveTicket(companyId: String, id: String?, input: TicketInput): String {
        val existing = id?.let { ticketDao.get(it) }
        val time = now()
        val entity = TicketEntity(
            id = existing?.id ?: newId(),
            companyId = existing?.companyId ?: companyId,
            clientId = input.clientId,
            number = existing?.number,
            title = input.title.trim(),
            description = input.description.trim().ifEmpty { null },
            priority = input.priority.serverId,
            status = input.status.serverId,
            dueAt = input.dueAt,
            closedAt = closedAtFor(input.status, existing?.closedAt, time),
            createdAt = existing?.createdAt ?: time,
            updatedAt = time,
            serverUpdatedAt = existing?.serverUpdatedAt,
            isDirty = true
        )
        ticketDao.upsert(entity)
        syncScheduler.requestSync()
        return entity.id
    }

    suspend fun setTicketStatus(id: String, status: TicketStatus) {
        val existing = ticketDao.get(id) ?: return
        val time = now()
        ticketDao.upsert(
            existing.copy(
                status = status.serverId,
                closedAt = closedAtFor(status, existing.closedAt, time),
                updatedAt = time,
                isDirty = true
            )
        )
        syncScheduler.requestSync()
    }

    suspend fun deleteTicket(id: String) {
        val existing = ticketDao.get(id) ?: return
        val time = now()
        ticketDao.upsert(existing.copy(deletedAt = time, updatedAt = time, isDirty = true))
        syncScheduler.requestSync()
    }

    /** Keeps the first closing time; reopening a ticket clears it. */
    private fun closedAtFor(status: TicketStatus, previous: Long?, time: Long): Long? =
        if (status.isOpen) null else previous ?: time

    // Appointments

    fun appointmentsFrom(companyId: String, fromMillis: Long): Flow<List<AppointmentWithClient>> =
        appointmentDao.observeFrom(companyId, fromMillis)

    fun appointmentsOfClient(clientId: String): Flow<List<AppointmentWithClient>> =
        appointmentDao.observeByClient(clientId)

    suspend fun appointment(id: String): AppointmentEntity? = appointmentDao.get(id)

    suspend fun saveAppointment(companyId: String, id: String?, input: AppointmentInput): String {
        val existing = id?.let { appointmentDao.get(it) }
        val time = now()
        val entity = AppointmentEntity(
            id = existing?.id ?: newId(),
            companyId = existing?.companyId ?: companyId,
            clientId = input.clientId,
            ticketId = input.ticketId,
            kind = input.kind.serverId,
            startsAt = input.startsAt,
            endsAt = input.endsAt.coerceAtLeast(input.startsAt),
            address = input.address.trim().ifEmpty { null },
            notes = input.notes.trim().ifEmpty { null },
            status = existing?.status ?: AppointmentStatus.SCHEDULED.serverId,
            reminderMinutes = input.reminder.minutes,
            createdAt = existing?.createdAt ?: time,
            updatedAt = time,
            serverUpdatedAt = existing?.serverUpdatedAt,
            isDirty = true
        )
        appointmentDao.upsert(entity)
        reminders.update(entity)
        syncScheduler.requestSync()
        return entity.id
    }

    suspend fun setAppointmentStatus(id: String, status: AppointmentStatus) {
        val existing = appointmentDao.get(id) ?: return
        val updated = existing.copy(status = status.serverId, updatedAt = now(), isDirty = true)
        appointmentDao.upsert(updated)
        reminders.update(updated)
        syncScheduler.requestSync()
    }

    suspend fun deleteAppointment(id: String) {
        val existing = appointmentDao.get(id) ?: return
        val time = now()
        appointmentDao.upsert(existing.copy(deletedAt = time, updatedAt = time, isDirty = true))
        reminders.cancel(id)
        syncScheduler.requestSync()
    }

    // Time entries

    fun timeEntries(companyId: String): Flow<List<TimeEntryWithClient>> = timeEntryDao.observeAll(companyId)
    fun timeEntriesOfClient(clientId: String): Flow<List<TimeEntryWithClient>> = timeEntryDao.observeByClient(clientId)
    fun timeEntriesOfTicket(ticketId: String): Flow<List<TimeEntryWithClient>> = timeEntryDao.observeByTicket(ticketId)

    fun runningTimer(companyId: String): Flow<TimeEntryWithClient?> =
        timeEntryDao.observeRunning(companyId, authRepository.currentUserId())

    suspend fun timeEntry(id: String): TimeEntryEntity? = timeEntryDao.get(id)

    /** Starts a timer, stopping any other one still running for this user. */
    suspend fun startTimer(companyId: String, clientId: String, ticketId: String?): String {
        val time = now()
        val userId = authRepository.currentUserId()
        val entity = TimeEntryEntity(
            id = newId(),
            companyId = companyId,
            clientId = clientId,
            ticketId = ticketId,
            userId = userId,
            startedAt = time,
            createdAt = time,
            updatedAt = time
        )
        database.withTransaction {
            stopRunningTimers(companyId, userId, time)
            timeEntryDao.upsert(entity)
        }
        syncScheduler.requestSync()
        return entity.id
    }

    suspend fun stopTimer(id: String) {
        val existing = timeEntryDao.get(id) ?: return
        if (existing.endedAt != null) return
        val time = now()
        timeEntryDao.upsert(existing.copy(endedAt = time, updatedAt = time, isDirty = true))
        syncScheduler.requestSync()
    }

    private suspend fun stopRunningTimers(companyId: String, userId: String?, time: Long) {
        timeEntryDao.allRunning(companyId).filter { it.userId == null || it.userId == userId }.forEach {
            timeEntryDao.upsert(it.copy(endedAt = time, updatedAt = time, isDirty = true))
        }
    }

    suspend fun saveTimeEntry(companyId: String, id: String?, input: TimeEntryInput): String {
        val existing = id?.let { timeEntryDao.get(it) }
        val time = now()
        val entity = TimeEntryEntity(
            id = existing?.id ?: newId(),
            companyId = existing?.companyId ?: companyId,
            clientId = input.clientId,
            ticketId = input.ticketId,
            userId = existing?.userId ?: authRepository.currentUserId(),
            startedAt = input.startedAt,
            endedAt = input.endedAt.coerceAtLeast(input.startedAt),
            note = input.note.trim().ifEmpty { null },
            createdAt = existing?.createdAt ?: time,
            updatedAt = time,
            serverUpdatedAt = existing?.serverUpdatedAt,
            isDirty = true
        )
        timeEntryDao.upsert(entity)
        syncScheduler.requestSync()
        return entity.id
    }

    suspend fun deleteTimeEntry(id: String) {
        val existing = timeEntryDao.get(id) ?: return
        val time = now()
        timeEntryDao.upsert(existing.copy(deletedAt = time, updatedAt = time, isDirty = true))
        syncScheduler.requestSync()
    }
}

package br.com.timasters.app.data.business

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ClientDao {
    @Query("SELECT * FROM clients WHERE companyId = :companyId AND deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeAll(companyId: String): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients WHERE id = :id")
    fun observe(id: String): Flow<ClientEntity?>

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun get(id: String): ClientEntity?

    @Upsert
    suspend fun upsert(entity: ClientEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ClientEntity>)

    @Query("SELECT * FROM clients WHERE companyId = :companyId AND isDirty = 1")
    suspend fun dirty(companyId: String): List<ClientEntity>

    @Query("SELECT id FROM clients WHERE id IN (:ids) AND isDirty = 1")
    suspend fun dirtyIds(ids: List<String>): List<String>

    /** Clears the flag only if the row was not edited again while it was being uploaded. */
    @Query("UPDATE clients SET isDirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM clients WHERE companyId = :companyId AND isDirty = 1")
    fun observeDirtyCount(companyId: String): Flow<Int>
}

@Dao
interface TicketDao {
    @Query(
        """SELECT t.*, c.name AS clientName FROM tickets t JOIN clients c ON c.id = t.clientId
           WHERE t.companyId = :companyId AND t.deletedAt IS NULL AND c.deletedAt IS NULL
           ORDER BY t.createdAt DESC"""
    )
    fun observeAll(companyId: String): Flow<List<TicketWithClient>>

    @Query(
        """SELECT t.*, c.name AS clientName FROM tickets t JOIN clients c ON c.id = t.clientId
           WHERE t.clientId = :clientId AND t.deletedAt IS NULL ORDER BY t.createdAt DESC"""
    )
    fun observeByClient(clientId: String): Flow<List<TicketWithClient>>

    @Query("SELECT t.*, c.name AS clientName FROM tickets t JOIN clients c ON c.id = t.clientId WHERE t.id = :id")
    fun observe(id: String): Flow<TicketWithClient?>

    @Query("SELECT * FROM tickets WHERE id = :id")
    suspend fun get(id: String): TicketEntity?

    @Query("SELECT * FROM tickets WHERE clientId = :clientId AND deletedAt IS NULL")
    suspend fun activeByClient(clientId: String): List<TicketEntity>

    @Upsert
    suspend fun upsert(entity: TicketEntity)

    @Upsert
    suspend fun upsertAll(entities: List<TicketEntity>)

    @Query("SELECT * FROM tickets WHERE companyId = :companyId AND isDirty = 1")
    suspend fun dirty(companyId: String): List<TicketEntity>

    @Query("SELECT id FROM tickets WHERE id IN (:ids) AND isDirty = 1")
    suspend fun dirtyIds(ids: List<String>): List<String>

    @Query("UPDATE tickets SET isDirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM tickets WHERE companyId = :companyId AND isDirty = 1")
    fun observeDirtyCount(companyId: String): Flow<Int>
}

@Dao
interface AppointmentDao {
    @Query(
        """SELECT a.*, c.name AS clientName, t.number AS ticketNumber, t.title AS ticketTitle
           FROM appointments a JOIN clients c ON c.id = a.clientId LEFT JOIN tickets t ON t.id = a.ticketId
           WHERE a.companyId = :companyId AND a.deletedAt IS NULL AND c.deletedAt IS NULL
             AND a.endsAt >= :fromMillis
           ORDER BY a.startsAt"""
    )
    fun observeFrom(companyId: String, fromMillis: Long): Flow<List<AppointmentWithClient>>

    @Query(
        """SELECT a.*, c.name AS clientName, t.number AS ticketNumber, t.title AS ticketTitle
           FROM appointments a JOIN clients c ON c.id = a.clientId LEFT JOIN tickets t ON t.id = a.ticketId
           WHERE a.clientId = :clientId AND a.deletedAt IS NULL ORDER BY a.startsAt DESC"""
    )
    fun observeByClient(clientId: String): Flow<List<AppointmentWithClient>>

    @Query("SELECT * FROM appointments WHERE id = :id")
    suspend fun get(id: String): AppointmentEntity?

    @Query("SELECT * FROM appointments WHERE clientId = :clientId AND deletedAt IS NULL")
    suspend fun activeByClient(clientId: String): List<AppointmentEntity>

    /** Upcoming reminders to (re)schedule, e.g. after a sync brought appointments from another phone. */
    @Query(
        """SELECT * FROM appointments WHERE companyId = :companyId AND deletedAt IS NULL
           AND status = 'agendado' AND reminderMinutes IS NOT NULL AND startsAt > :nowMillis"""
    )
    suspend fun withPendingReminders(companyId: String, nowMillis: Long): List<AppointmentEntity>

    @Upsert
    suspend fun upsert(entity: AppointmentEntity)

    @Upsert
    suspend fun upsertAll(entities: List<AppointmentEntity>)

    @Query("SELECT * FROM appointments WHERE companyId = :companyId AND isDirty = 1")
    suspend fun dirty(companyId: String): List<AppointmentEntity>

    @Query("SELECT id FROM appointments WHERE id IN (:ids) AND isDirty = 1")
    suspend fun dirtyIds(ids: List<String>): List<String>

    @Query("UPDATE appointments SET isDirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM appointments WHERE companyId = :companyId AND isDirty = 1")
    fun observeDirtyCount(companyId: String): Flow<Int>
}

@Dao
interface TimeEntryDao {
    @Query(
        """SELECT e.*, c.name AS clientName, t.number AS ticketNumber, t.title AS ticketTitle
           FROM time_entries e JOIN clients c ON c.id = e.clientId LEFT JOIN tickets t ON t.id = e.ticketId
           WHERE e.companyId = :companyId AND e.deletedAt IS NULL AND c.deletedAt IS NULL
           ORDER BY e.startedAt DESC"""
    )
    fun observeAll(companyId: String): Flow<List<TimeEntryWithClient>>

    @Query(
        """SELECT e.*, c.name AS clientName, t.number AS ticketNumber, t.title AS ticketTitle
           FROM time_entries e JOIN clients c ON c.id = e.clientId LEFT JOIN tickets t ON t.id = e.ticketId
           WHERE e.clientId = :clientId AND e.deletedAt IS NULL ORDER BY e.startedAt DESC"""
    )
    fun observeByClient(clientId: String): Flow<List<TimeEntryWithClient>>

    @Query(
        """SELECT e.*, c.name AS clientName, t.number AS ticketNumber, t.title AS ticketTitle
           FROM time_entries e JOIN clients c ON c.id = e.clientId LEFT JOIN tickets t ON t.id = e.ticketId
           WHERE e.ticketId = :ticketId AND e.deletedAt IS NULL ORDER BY e.startedAt DESC"""
    )
    fun observeByTicket(ticketId: String): Flow<List<TimeEntryWithClient>>

    /** The running timer of this user, if any. */
    @Query(
        """SELECT e.*, c.name AS clientName, t.number AS ticketNumber, t.title AS ticketTitle
           FROM time_entries e JOIN clients c ON c.id = e.clientId LEFT JOIN tickets t ON t.id = e.ticketId
           WHERE e.companyId = :companyId AND e.endedAt IS NULL AND e.deletedAt IS NULL
             AND (e.userId = :userId OR e.userId IS NULL)
           ORDER BY e.startedAt DESC LIMIT 1"""
    )
    fun observeRunning(companyId: String, userId: String?): Flow<TimeEntryWithClient?>

    @Query("SELECT * FROM time_entries WHERE companyId = :companyId AND endedAt IS NULL AND deletedAt IS NULL")
    suspend fun allRunning(companyId: String): List<TimeEntryEntity>

    @Query("SELECT * FROM time_entries WHERE id = :id")
    suspend fun get(id: String): TimeEntryEntity?

    @Query("SELECT * FROM time_entries WHERE clientId = :clientId AND deletedAt IS NULL")
    suspend fun activeByClient(clientId: String): List<TimeEntryEntity>

    @Query("SELECT * FROM time_entries WHERE ticketId = :ticketId AND deletedAt IS NULL")
    suspend fun activeByTicket(ticketId: String): List<TimeEntryEntity>

    @Upsert
    suspend fun upsert(entity: TimeEntryEntity)

    @Upsert
    suspend fun upsertAll(entities: List<TimeEntryEntity>)

    @Query("SELECT * FROM time_entries WHERE companyId = :companyId AND isDirty = 1")
    suspend fun dirty(companyId: String): List<TimeEntryEntity>

    @Query("SELECT id FROM time_entries WHERE id IN (:ids) AND isDirty = 1")
    suspend fun dirtyIds(ids: List<String>): List<String>

    @Query("UPDATE time_entries SET isDirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM time_entries WHERE companyId = :companyId AND isDirty = 1")
    fun observeDirtyCount(companyId: String): Flow<Int>
}

package br.com.timasters.app.data.business

import br.com.timasters.app.core.time.IsoTime
import br.com.timasters.app.data.business.sync.TicketRow
import br.com.timasters.app.data.business.sync.TimeEntryRow
import br.com.timasters.app.data.business.sync.toEntity
import br.com.timasters.app.data.business.sync.toPush
import br.com.timasters.app.ui.screens.business.BusinessTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BusinessSyncMappingTest {

    @Test
    fun isoTime_roundTripsAndReadsPostgresFormat() {
        val millis = 1_800_000_000_123L
        assertEquals(millis, IsoTime.parse(IsoTime.format(millis)))
        assertEquals(millis, IsoTime.parse("2027-01-15T08:00:00.123456+00:00"))
        assertNull(IsoTime.parse("ontem"))
    }

    @Test
    fun ticketPush_sendsOnlyWritableFieldsWithServerValues() {
        val entity = TicketEntity(
            id = "t1", companyId = "c1", clientId = "cl1", number = 7, title = "Sem internet",
            priority = TicketPriority.URGENT.serverId, status = TicketStatus.RESOLVED.serverId,
            dueAt = 1_800_000_000_000L, closedAt = null, createdAt = 1L, updatedAt = 2L, deletedAt = 1_800_000_000_000L
        )
        val push = entity.toPush()
        assertEquals("urgente", push.priority)
        assertEquals("resolvido", push.status)
        assertEquals("2027-01-15T08:00:00Z", push.dueAt)
        assertEquals("2027-01-15T08:00:00Z", push.deletedAt)
        assertNull(push.closedAt)
    }

    @Test
    fun ticketRow_becomesCleanLocalRowWithServerNumber() {
        val row = TicketRow(
            id = "t1", companyId = "c1", clientId = "cl1", number = 12, title = "Trocar roteador",
            priority = "alta", status = "aberto", createdAt = "2027-01-15T08:00:00+00:00",
            updatedAt = "2027-01-15T09:00:00.5+00:00"
        )
        val entity = row.toEntity()
        assertEquals(12, entity.number)
        assertFalse(entity.isDirty)
        assertEquals("2027-01-15T09:00:00.5+00:00", entity.serverUpdatedAt)
        assertNull(entity.deletedAt)
        assertEquals(TicketPriority.HIGH, TicketPriority.from(entity.priority))
    }

    @Test
    fun runningTimeEntry_hasNoEndAndCountsUntilNow() {
        val row = TimeEntryRow(
            id = "e1", companyId = "c1", clientId = "cl1", startedAt = "2027-01-15T08:00:00Z",
            createdAt = "2027-01-15T08:00:00Z", updatedAt = "2027-01-15T08:00:00Z"
        )
        val entity = row.toEntity()
        assertNull(entity.endedAt)
        assertEquals(90 * 60_000L, entity.durationMillis(now = entity.startedAt + 90 * 60_000L))
        assertNull(entity.toPush().endedAt)
    }

    @Test
    fun unknownServerValues_fallBackToDefaults() {
        assertEquals(TicketStatus.OPEN, TicketStatus.from("inexistente"))
        assertEquals(AppointmentKind.ON_SITE, AppointmentKind.from(null))
        assertEquals(ReminderOption.HOUR_1, ReminderOption.from(60))
        assertEquals(ReminderOption.NONE, ReminderOption.from(7))
    }

    @Test
    fun durations_areFormattedForPeople() {
        assertEquals("45min", BusinessTime.formatDuration(45 * 60_000L))
        assertEquals("1h 05min", BusinessTime.formatDuration(65 * 60_000L))
        assertEquals("01:05:09", BusinessTime.formatClock((3600 + 5 * 60 + 9) * 1000L))
    }
}

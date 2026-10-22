package br.com.timasters.app.ui.screens.business

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.data.business.TicketPriority
import br.com.timasters.app.data.business.TicketStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Business dates use the phone's time zone (technicians work where they are). */
internal object BusinessTime {
    val zone: ZoneId get() = ZoneId.systemDefault()

    private val time = DateTimeFormatter.ofPattern("HH:mm", BrFormatters.LOCALE_PT_BR)
    private val date = DateTimeFormatter.ofPattern("dd/MM/yyyy", BrFormatters.LOCALE_PT_BR)

    fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    fun localTime(millis: Long): LocalTime = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
    fun toMillis(date: LocalDate, time: LocalTime): Long = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    fun startOfToday(): Long = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    fun formatTime(millis: Long): String = time.format(Instant.ofEpochMilli(millis).atZone(zone))
    fun formatDate(millis: Long): String = date.format(Instant.ofEpochMilli(millis).atZone(zone))
    fun formatDateTime(millis: Long): String = "${formatDate(millis)} ${formatTime(millis)}"

    /** Material date pickers work with UTC midnight of the chosen day. */
    fun pickerMillisToDate(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    fun dateToPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun weekdayShort(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, BrFormatters.LOCALE_PT_BR).removeSuffix(".")

    /** "1h 05min", "45min". */
    fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes.toString().padStart(2, '0')}min" else "${minutes}min"
    }

    /** "01:05:09" for the running timer. */
    fun formatClock(millis: Long): String {
        val seconds = millis / 1000
        return "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }
}

/** "Hoje", "Amanhã", "Ontem" or "qua, 22/10/2026". */
@Composable
internal fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now(BusinessTime.zone)
    return when (date) {
        today -> stringResource(id = R.string.day_today)
        today.plusDays(1) -> stringResource(id = R.string.day_tomorrow)
        today.minusDays(1) -> stringResource(id = R.string.day_yesterday)
        else -> "${BusinessTime.weekdayShort(date)}, ${BusinessTime.formatDate(BusinessTime.toMillis(date, LocalTime.NOON))}"
    }
}

@Composable
internal fun priorityColor(priority: TicketPriority): Color = when (priority) {
    TicketPriority.URGENT -> MaterialTheme.colorScheme.error
    TicketPriority.HIGH -> Color(0xFFE65100)
    TicketPriority.MEDIUM -> MaterialTheme.colorScheme.primary
    TicketPriority.LOW -> MaterialTheme.colorScheme.outline
}

@Composable
internal fun statusColor(status: TicketStatus): Color = when (status) {
    TicketStatus.OPEN -> MaterialTheme.colorScheme.primary
    TicketStatus.IN_PROGRESS -> MaterialTheme.colorScheme.tertiary
    TicketStatus.WAITING -> Color(0xFFF9A825)
    TicketStatus.RESOLVED, TicketStatus.CLOSED -> Color(0xFF2E7D32)
}

@Composable
internal fun ticketLabel(number: Int?, title: String): String =
    if (number != null) "#$number · $title" else stringResource(id = R.string.ticket_number_pending, title)

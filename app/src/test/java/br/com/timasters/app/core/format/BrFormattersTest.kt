package br.com.timasters.app.core.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

class BrFormattersTest {

    private val zone = ZoneId.of("America/Sao_Paulo")

    @Test
    fun formatDate_usesDayMonthYear() {
        val millis = LocalDateTime.of(2026, 3, 5, 14, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("05/03/2026", BrFormatters.formatDate(millis, zone))
        assertEquals("05/03/2026 14:30", BrFormatters.formatDateTime(millis, zone))
    }

    @Test
    fun formatDate_defaultsToSaoPauloTime() {
        // 02:00 UTC on 06/03 is still 05/03 23:00 in São Paulo (UTC-3).
        val millis = LocalDateTime.of(2026, 3, 6, 2, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("05/03/2026 23:00", BrFormatters.formatDateTime(millis))
    }

    @Test
    fun formatCurrency_usesRealWithCommaDecimals() {
        assertEquals("R$ 1.234,56", BrFormatters.formatCurrency(BigDecimal("1234.56")))
        assertEquals("R$ 0,50", BrFormatters.formatCurrency(0.5))
    }

    @Test
    fun formatPhone_handlesMobileLandlineAndCountryCode() {
        assertEquals("(11) 98765-4321", BrFormatters.formatPhone("11987654321"))
        assertEquals("(11) 3333-4444", BrFormatters.formatPhone("1133334444"))
        assertEquals("(21) 99999-0000", BrFormatters.formatPhone("+55 21 99999-0000"))
        assertEquals("123", BrFormatters.formatPhone("123"))
    }
}

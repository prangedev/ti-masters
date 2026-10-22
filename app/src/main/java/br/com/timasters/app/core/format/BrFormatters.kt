package br.com.timasters.app.core.format

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Brazilian display formats (dd/MM/yyyy, R$, phone). Timestamps are shown in São Paulo time. */
object BrFormatters {

    val LOCALE_PT_BR: Locale = Locale.forLanguageTag("pt-BR")
    val ZONE_SAO_PAULO: ZoneId = ZoneId.of("America/Sao_Paulo")

    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", LOCALE_PT_BR)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", LOCALE_PT_BR)

    fun formatDate(epochMillis: Long, zone: ZoneId = ZONE_SAO_PAULO): String =
        dateFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun formatDateTime(epochMillis: Long, zone: ZoneId = ZONE_SAO_PAULO): String =
        dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** "R$ 1.234,56". The JDK uses a non-breaking space after "R$"; it is normalized to a regular space. */
    fun formatCurrency(amount: BigDecimal): String =
        NumberFormat.getCurrencyInstance(LOCALE_PT_BR).format(amount).replace(' ', ' ')

    fun formatCurrency(amount: Double): String = formatCurrency(BigDecimal.valueOf(amount))

    /** "1.234,5" style number with a fixed number of decimals. */
    fun formatNumber(value: Double, decimals: Int = 1): String =
        String.format(LOCALE_PT_BR, "%,.${decimals}f", value)

    /**
     * "(11) 98765-4321" for mobiles, "(11) 3333-4444" for landlines.
     * Accepts an optional +55 country code. Unknown shapes are returned unchanged.
     */
    fun formatPhone(raw: String): String {
        var digits = raw.filter { it.isDigit() }
        if (digits.length in 12..13 && digits.startsWith("55")) digits = digits.substring(2)
        return when (digits.length) {
            11 -> "(${digits.substring(0, 2)}) ${digits.substring(2, 7)}-${digits.substring(7)}"
            10 -> "(${digits.substring(0, 2)}) ${digits.substring(2, 6)}-${digits.substring(6)}"
            else -> raw
        }
    }
}

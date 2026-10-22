package br.com.timasters.app.core.format

import java.math.BigDecimal
import java.math.RoundingMode

/** Parsing of what people type in money, quantity and hour fields (Brazilian style). */
object MoneyInput {

    /** "R$ 1.234,56", "1234,5", "1234.56", "150" → cents; null when it is not a number. */
    fun parseCents(text: String): Long? =
        parseDecimal(text.replace("R$", ""))?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.toLong()

    /** Cents → "1234,56" for editing (no thousands separator, so it parses back). */
    fun centsToInput(cents: Long): String =
        BigDecimal.valueOf(cents).movePointLeft(2).setScale(2).toPlainString().replace('.', ',')

    fun formatCents(cents: Long): String = BrFormatters.formatCurrency(BigDecimal.valueOf(cents).movePointLeft(2))

    /** "2", "1,5", "0.25" → 1.5 etc. */
    fun parseQuantity(text: String): Double? = parseDecimal(text)?.toDouble()

    /** 1.0 → "1", 1.5 → "1,5". Rounded to 3 decimals (stock sums of decimals drift slightly). */
    fun formatQuantity(value: Double): String =
        BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
            .toPlainString().replace('.', ',').let { if (it == "-0") "0" else it }

    /** Hours typed as "1:30", "1h30", "1,5" or "2" → minutes. */
    fun parseHoursToMinutes(text: String): Int? {
        val clean = text.trim().lowercase().removeSuffix("min").removeSuffix("h").trim()
        if (clean.isEmpty()) return 0
        val separator = clean.indexOfFirst { it == ':' || it == 'h' }
        if (separator >= 0) {
            val hours = clean.substring(0, separator).trim().ifEmpty { "0" }.toIntOrNull() ?: return null
            val minutes = clean.substring(separator + 1).trim().ifEmpty { "0" }.toIntOrNull() ?: return null
            if (hours < 0 || minutes !in 0..59) return null
            return hours * 60 + minutes
        }
        val hours = parseDecimal(clean) ?: return null
        return hours.multiply(BigDecimal(60)).setScale(0, RoundingMode.HALF_UP).toInt()
    }

    /** 90 → "1:30". */
    fun minutesToInput(minutes: Int): String = "%d:%02d".format(minutes / 60, minutes % 60)

    /**
     * Accepts both "1.234,56" (Brazilian) and "1234.56". With only dots, a dot followed by exactly
     * three digits is a thousands separator ("1.500" = 1500).
     */
    private fun parseDecimal(text: String): BigDecimal? {
        val clean = text.trim().replace(" ", "")
        if (clean.isEmpty() || clean.startsWith("-")) return null
        val normalized = when {
            ',' in clean -> clean.replace(".", "").replace(',', '.')
            clean.count { it == '.' } == 1 && clean.substringAfter('.').length != 3 -> clean
            else -> clean.replace(".", "")
        }
        return normalized.toBigDecimalOrNull()
    }
}

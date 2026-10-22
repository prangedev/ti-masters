package br.com.timasters.app.core.time

import java.time.Instant
import java.time.OffsetDateTime

/** Conversion between epoch millis (stored on the phone) and ISO-8601 text (used by the server). */
object IsoTime {

    fun format(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    /** Accepts "2026-10-19T03:24:01.166Z" and Postgres' "2026-10-19T03:24:01.166177+00:00". */
    fun parse(value: String?): Long? =
        value?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
}

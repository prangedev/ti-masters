package br.com.timasters.app.data.plan

import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** What the app shows and unlocks right now. */
data class Entitlement(
    /** Plan in effect (the debug simulation, when active, otherwise [accountPlan]). */
    val plan: SubscriptionPlan,
    /** Plan of the signed-in account according to the last server check still within validity. */
    val accountPlan: SubscriptionPlan,
    /** When the server last confirmed the plan for this user, or null if never. */
    val verifiedAtMillis: Long?,
    /** True only in debug builds while a plan is being simulated on this device. */
    val isSimulated: Boolean
)

/**
 * Last plan confirmed by the server, kept on the device so the app works offline.
 * The cache is ignored for another user, after [OFFLINE_VALIDITY_MILLIS] without a check,
 * a few days after the subscription's own expiry, or when the clock was moved backwards.
 */
data class EntitlementCache(
    val userId: String,
    val plan: SubscriptionPlan,
    val expiresAtMillis: Long?,
    val verifiedAtMillis: Long
) {
    fun planFor(currentUserId: String?, nowMillis: Long): SubscriptionPlan {
        val isValid = currentUserId == userId &&
            nowMillis >= verifiedAtMillis - CLOCK_TOLERANCE_MILLIS &&
            nowMillis < verifiedAtMillis + OFFLINE_VALIDITY_MILLIS &&
            (expiresAtMillis == null || nowMillis < expiresAtMillis + EXPIRY_GRACE_MILLIS)
        return if (isValid) plan else SubscriptionPlan.BASIC
    }

    companion object {
        /** Paid features keep working this long without internet. */
        val OFFLINE_VALIDITY_MILLIS = TimeUnit.DAYS.toMillis(7)

        /** Time for Google Play renewals to reach our server after the paid period ends. */
        val EXPIRY_GRACE_MILLIS = TimeUnit.DAYS.toMillis(3)

        /** Small clock corrections (time zone, NTP) must not drop the plan. */
        val CLOCK_TOLERANCE_MILLIS = TimeUnit.HOURS.toMillis(24)

        /** Parses Postgres timestamptz JSON values such as "2026-11-18T03:24:01.166177+00:00". */
        fun parseServerTimestamp(value: String?): Long? =
            value?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    }
}

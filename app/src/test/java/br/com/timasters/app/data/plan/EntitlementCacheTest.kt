package br.com.timasters.app.data.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class EntitlementCacheTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val verifiedAt = 1_800_000_000_000L
    private val cache = EntitlementCache(
        userId = "user-a",
        plan = SubscriptionPlan.BUSINESS,
        expiresAtMillis = verifiedAt + 30 * day,
        verifiedAtMillis = verifiedAt
    )

    @Test
    fun recentCheck_keepsPaidPlanOffline() {
        assertEquals(SubscriptionPlan.BUSINESS, cache.planFor("user-a", verifiedAt + 6 * day))
    }

    @Test
    fun anotherUser_neverInheritsThePlan() {
        assertEquals(SubscriptionPlan.BASIC, cache.planFor("user-b", verifiedAt + day))
        assertEquals(SubscriptionPlan.BASIC, cache.planFor(null, verifiedAt + day))
    }

    @Test
    fun withoutCheckingForSevenDays_fallsBackToBasic() {
        assertEquals(SubscriptionPlan.BASIC, cache.planFor("user-a", verifiedAt + 7 * day))
    }

    @Test
    fun subscriptionExpiry_allowsThreeDaysForRenewalToArrive() {
        val expiring = cache.copy(expiresAtMillis = verifiedAt + day)

        assertEquals(SubscriptionPlan.BUSINESS, expiring.planFor("user-a", verifiedAt + 3 * day))
        assertEquals(SubscriptionPlan.BASIC, expiring.planFor("user-a", verifiedAt + 4 * day + 1))
    }

    @Test
    fun clockMovedFarBackwards_fallsBackToBasic() {
        assertEquals(SubscriptionPlan.BUSINESS, cache.planFor("user-a", verifiedAt - TimeUnit.HOURS.toMillis(2)))
        assertEquals(SubscriptionPlan.BASIC, cache.planFor("user-a", verifiedAt - 2 * day))
    }

    @Test
    fun parseServerTimestamp_readsPostgresFormat() {
        assertEquals(
            1_800_000_000_123L,
            EntitlementCache.parseServerTimestamp("2027-01-15T08:00:00.123456+00:00")
        )
        assertEquals(
            1_800_000_000_000L,
            EntitlementCache.parseServerTimestamp("2027-01-15T05:00:00-03:00")
        )
        assertNull(EntitlementCache.parseServerTimestamp("not a date"))
        assertNull(EntitlementCache.parseServerTimestamp(null))
    }
}

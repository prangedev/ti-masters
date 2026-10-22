package br.com.timasters.app.data.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionPlanTest {

    @Test
    fun basicPlan_hasEssentialToolsAndAds() {
        val plan = SubscriptionPlan.BASIC

        assertTrue(plan.has(Feature.SPEED_TEST))
        assertTrue(plan.has(Feature.LAN_SCAN))
        assertFalse(plan.has(Feature.HEATMAP))
        assertFalse(plan.has(Feature.ROUTERS))
        assertFalse(plan.has(Feature.ROUTERS_FIBER))
        assertFalse(plan.has(Feature.BUSINESS_SUITE))
        // Calculators/references are free; network tests need Advanced.
        assertFalse(plan.has(Feature.NETWORK_TOOLS))
        assertTrue(plan.showsAds)
    }

    @Test
    fun advancedPlan_unlocksToolsButNotBusiness() {
        val plan = SubscriptionPlan.ADVANCED

        assertTrue(plan.has(Feature.HEATMAP))
        assertTrue(plan.has(Feature.ROUTERS))
        assertTrue(plan.has(Feature.VPN))
        assertTrue(plan.has(Feature.NETWORK_TOOLS))
        assertFalse(plan.has(Feature.BUSINESS_SUITE))
        // GPON/EPON equipment (ONT/ONU) is Business-only.
        assertFalse(plan.has(Feature.ROUTERS_FIBER))
        assertFalse(plan.showsAds)
    }

    @Test
    fun businessPlan_hasEveryFeature() {
        val plan = SubscriptionPlan.BUSINESS

        assertEquals(Feature.entries.toSet(), plan.features)
        assertFalse(plan.showsAds)
    }

    @Test
    fun fromServerId_mapsKnownValuesAndFallsBackToBasic() {
        assertEquals(SubscriptionPlan.ADVANCED, SubscriptionPlan.fromServerId("advanced"))
        assertEquals(SubscriptionPlan.BUSINESS, SubscriptionPlan.fromServerId("business"))
        assertEquals(SubscriptionPlan.BASIC, SubscriptionPlan.fromServerId("enterprise"))
        assertEquals(SubscriptionPlan.BASIC, SubscriptionPlan.fromServerId(null))
    }
}

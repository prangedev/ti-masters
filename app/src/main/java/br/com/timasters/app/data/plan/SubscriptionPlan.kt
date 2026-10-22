package br.com.timasters.app.data.plan

import androidx.annotation.StringRes
import br.com.timasters.app.R

enum class SubscriptionPlan(
    /** Value stored in the `subscriptions.plan` column on the server. */
    val serverId: String,
    @param:StringRes val titleResId: Int,
    @param:StringRes val priceResId: Int,
    @param:StringRes val descriptionResId: Int,
    val featureResIds: List<Int>
) {
    BASIC(
        serverId = "basic",
        titleResId = R.string.plan_basic_title,
        priceResId = R.string.plan_basic_price,
        descriptionResId = R.string.plan_basic_desc,
        featureResIds = listOf(
            R.string.plan_basic_feature_diagnostics,
            R.string.plan_basic_feature_network,
            R.string.plan_basic_feature_ads
        )
    ),
    ADVANCED(
        serverId = "advanced",
        titleResId = R.string.plan_advanced_title,
        priceResId = R.string.plan_advanced_price,
        descriptionResId = R.string.plan_advanced_desc,
        featureResIds = listOf(
            R.string.plan_advanced_feature_no_ads,
            R.string.plan_advanced_feature_diagnostics,
            R.string.plan_advanced_feature_heatmap,
            R.string.plan_advanced_feature_routers,
            R.string.plan_advanced_feature_tools
        )
    ),
    BUSINESS(
        serverId = "business",
        titleResId = R.string.plan_business_title,
        priceResId = R.string.plan_business_price,
        descriptionResId = R.string.plan_business_desc,
        featureResIds = listOf(
            R.string.plan_business_feature_all_advanced,
            R.string.plan_business_feature_crm,
            R.string.plan_business_feature_orders,
            R.string.plan_business_feature_branding,
            R.string.plan_business_feature_fiber,
            R.string.plan_business_feature_card
        )
    );

    val features: Set<Feature>
        get() = when (this) {
            BASIC -> BASIC_FEATURES
            ADVANCED -> ADVANCED_FEATURES
            BUSINESS -> Feature.entries.toSet()
        }

    fun has(feature: Feature): Boolean = feature in features

    val showsAds: Boolean get() = !has(Feature.AD_FREE)

    companion object {
        private val BASIC_FEATURES = setOf(Feature.SPEED_TEST, Feature.WIFI_LIVE_SIGNAL, Feature.LAN_SCAN)
        private val BUSINESS_ONLY = setOf(Feature.BUSINESS_SUITE, Feature.ROUTERS_FIBER)
        private val ADVANCED_FEATURES = Feature.entries.toSet() - BUSINESS_ONLY

        /** Unknown values (e.g. a plan added on the server later) fall back to BASIC. */
        fun fromServerId(value: String?): SubscriptionPlan =
            entries.firstOrNull { it.serverId == value } ?: BASIC
    }
}

package br.com.timasters.app.data.plan

/**
 * Every capability that depends on the plan. Screens ask `plan.has(Feature.X)` instead of
 * comparing plans, so moving a feature between plans is a one-line change in [SubscriptionPlan].
 */
enum class Feature {
    // Basic (all plans)
    SPEED_TEST,
    WIFI_LIVE_SIGNAL,
    LAN_SCAN,

    // Advanced
    AD_FREE,
    TEST_HISTORY_FULL,
    SPECTRUM_FULL,
    SPEED_FACTORS_FULL,
    PORT_SCANNER,
    UNIFI_TOOLS,
    BLUETOOTH_SCAN,
    ROAMING_ANALYSIS,
    HEATMAP,
    ROUTERS,
    VPN,

    /** Toolbox network tests: traceroute, DNS, HTTPS/certificate, Wake-on-LAN, MTU. */
    NETWORK_TOOLS,

    // Business: clients, tickets, timer, schedule, pricing, service orders, branding, card, quotes
    BUSINESS_SUITE,

    // Business: GPON/EPON equipment (ONT/ONU) in Routers: logins, guides and firmware
    ROUTERS_FIBER
}

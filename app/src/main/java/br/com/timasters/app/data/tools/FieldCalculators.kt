package br.com.timasters.app.data.tools

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

// region Fiber (GPON) power budget

/** Typical attenuation per km used in GPON projects. */
enum class Wavelength(val nm: Int, val dbPerKm: Double) { NM1310(1310, 0.35), NM1490(1490, 0.25), NM1550(1550, 0.22) }

/** Typical insertion loss of balanced PLC splitters. */
enum class Splitter(val label: String, val lossDb: Double) {
    S1X2("1:2", 3.7), S1X4("1:4", 7.3), S1X8("1:8", 10.5), S1X16("1:16", 13.7), S1X32("1:32", 17.1), S1X64("1:64", 20.5)
}

data class FiberBudgetInput(
    val txDbm: Double = 3.0,
    val lengthKm: Double,
    val wavelength: Wavelength = Wavelength.NM1490,
    val connectors: Int = 2,
    val connectorLossDb: Double = 0.5,
    val splices: Int = 0,
    val spliceLossDb: Double = 0.1,
    val splitters: List<Splitter> = emptyList(),
    /** ONU sensitivity: -27 dBm for GPON class B+, -28/-30 for C+. */
    val rxSensitivityDbm: Double = -27.0,
    val safetyMarginDb: Double = 3.0
)

enum class BudgetStatus { OK, AT_LIMIT, FAIL, OVERLOAD }

data class FiberBudgetResult(
    val fiberLoss: Double,
    val connectorsLoss: Double,
    val splicesLoss: Double,
    val splittersLoss: Double,
    val totalLoss: Double,
    val expectedRxDbm: Double,
    val marginDb: Double,
    val status: BudgetStatus
)

object FiberBudget {
    /** ONUs saturate above about -8 dBm. */
    const val OVERLOAD_DBM = -8.0

    fun calculate(input: FiberBudgetInput): FiberBudgetResult {
        val fiber = input.lengthKm * input.wavelength.dbPerKm
        val connectors = input.connectors * input.connectorLossDb
        val splices = input.splices * input.spliceLossDb
        val splitters = input.splitters.sumOf { it.lossDb }
        val total = fiber + connectors + splices + splitters
        val rx = input.txDbm - total
        val margin = rx - input.rxSensitivityDbm
        val status = when {
            rx > OVERLOAD_DBM -> BudgetStatus.OVERLOAD
            margin >= input.safetyMarginDb -> BudgetStatus.OK
            margin >= 0 -> BudgetStatus.AT_LIMIT
            else -> BudgetStatus.FAIL
        }
        return FiberBudgetResult(fiber, connectors, splices, splitters, total, rx, margin, status)
    }
}

// endregion

// region Point-to-point radio link

data class RadioLinkInput(
    val frequencyGhz: Double,
    val distanceKm: Double,
    val txPowerDbm: Double,
    val txAntennaDbi: Double,
    val rxAntennaDbi: Double,
    val cableLossDb: Double = 0.0,
    val rxSensitivityDbm: Double = -80.0
)

data class RadioLinkResult(
    val freeSpaceLossDb: Double,
    val expectedRssiDbm: Double,
    val fadeMarginDb: Double,
    /** First Fresnel zone radius at mid-path, and the 60% that must be free of obstacles. */
    val fresnelRadiusM: Double,
    val fresnelClearanceM: Double,
    /** Earth curvature at mid-path (k = 4/3). */
    val earthBulgeM: Double
) {
    /** Minimum height of the line of sight above obstacles at mid-path. */
    val minClearanceM: Double get() = fresnelClearanceM + earthBulgeM
}

object RadioLink {
    fun calculate(input: RadioLinkInput): RadioLinkResult {
        require(input.frequencyGhz > 0 && input.distanceKm > 0)
        val fspl = 20 * log10(input.distanceKm) + 20 * log10(input.frequencyGhz * 1000) + 32.44
        val rssi = input.txPowerDbm + input.txAntennaDbi + input.rxAntennaDbi - input.cableLossDb - fspl
        val fresnel = 8.656 * sqrt(input.distanceKm / input.frequencyGhz)
        val bulge = input.distanceKm.pow(2) * 1000 / (8 * (4.0 / 3.0) * EARTH_RADIUS_KM)
        return RadioLinkResult(fspl, rssi, rssi - input.rxSensitivityDbm, fresnel, fresnel * 0.6, bulge)
    }

    private const val EARTH_RADIUS_KM = 6371.0
}

// endregion

// region CCTV storage

/** Rough per-camera bitrates (Mbps) for continuous recording; the recorder's own numbers win. */
enum class CameraPreset(val label: String, val h264Mbps: Double, val h265Mbps: Double) {
    HD_720("720p (1 MP)", 2.0, 1.0),
    FHD_1080("1080p (2 MP)", 4.0, 2.0),
    QHD_4MP("4 MP", 6.0, 3.0),
    UHD_4K("4K (8 MP)", 12.0, 6.0)
}

data class CctvInput(
    val cameras: Int,
    val bitrateMbps: Double,
    val hoursPerDay: Double = 24.0,
    val days: Int = 30,
    /** 100 = continuous; motion-only recording usually lands between 30% and 50%. */
    val recordingPercent: Double = 100.0
)

data class CctvResult(val requiredTb: Double, val suggestedDiskTb: Int?, val dailyGb: Double, val totalMbps: Double)

object CctvStorage {
    /** Common HDD sizes sold for recorders. */
    val DISK_SIZES_TB = listOf(1, 2, 3, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24)

    /** A "1 TB" disk shows about 0.93 TB usable in the recorder (TB vs TiB plus formatting). */
    const val USABLE_FACTOR = 0.93

    fun calculate(input: CctvInput): CctvResult {
        val totalMbps = input.cameras * input.bitrateMbps
        val dailyBytes = totalMbps * 1e6 / 8 * 3600 * input.hoursPerDay * (input.recordingPercent / 100)
        val requiredTb = dailyBytes * input.days / 1e12
        val disk = DISK_SIZES_TB.firstOrNull { it * USABLE_FACTOR >= requiredTb }
        return CctvResult(requiredTb, disk, dailyBytes / 1e9, totalMbps)
    }

    /** How many days fit in [diskTb] with the given cameras. */
    fun daysFor(diskTb: Double, input: CctvInput): Double {
        val daily = calculate(input.copy(days = 1)).requiredTb
        return if (daily <= 0) 0.0 else diskTb * USABLE_FACTOR / daily
    }
}

// endregion

// region PoE budget

enum class PoeStandard(val label: String, val pseWatts: Double, val pdWatts: Double) {
    AF("802.3af (PoE)", 15.4, 12.95),
    AT("802.3at (PoE+)", 30.0, 25.5),
    BT3("802.3bt Tipo 3", 60.0, 51.0),
    BT4("802.3bt Tipo 4", 90.0, 71.3)
}

data class PoeDevice(val name: String, val count: Int, val watts: Double)

data class PoeResult(val totalWatts: Double, val budgetWatts: Double, val remainingWatts: Double, val usagePercent: Double) {
    val fits: Boolean get() = remainingWatts >= 0

    /** Keep about 20% spare for start-up peaks, IR at night and heaters. */
    val comfortable: Boolean get() = usagePercent <= 80
}

object PoeBudget {
    fun calculate(budgetWatts: Double, devices: List<PoeDevice>): PoeResult {
        val total = devices.sumOf { it.count * it.watts }
        return PoeResult(total, budgetWatts, budgetWatts - total, if (budgetWatts > 0) total / budgetWatts * 100 else 0.0)
    }

    /** The smallest standard whose per-port power covers a device. */
    fun standardFor(watts: Double): PoeStandard? = PoeStandard.entries.firstOrNull { it.pdWatts >= watts }
}

// endregion

// region UPS runtime

data class UpsInput(
    val loadWatts: Double,
    val batteryAh: Double,
    val batteryVolts: Double = 12.0,
    val batteries: Int = 1,
    /** Inverter efficiency. */
    val efficiency: Double = 0.85,
    /** Lead-acid batteries shouldn't go below ~20% charge. */
    val usableFraction: Double = 0.8,
    val upsCapacityVa: Double? = null,
    val powerFactor: Double = 0.6
)

data class UpsResult(val runtimeMinutes: Double, val loadPercent: Double?) {
    val overloaded: Boolean get() = (loadPercent ?: 0.0) > 100
}

object UpsRuntime {
    fun calculate(input: UpsInput): UpsResult {
        require(input.loadWatts > 0)
        val energyWh = input.batteryAh * input.batteryVolts * input.batteries * input.efficiency * input.usableFraction
        val load = input.upsCapacityVa?.takeIf { it > 0 }?.let { input.loadWatts / (it * input.powerFactor) * 100 }
        return UpsResult(energyWh / input.loadWatts * 60, load)
    }
}

// endregion

// region Converters

object Converters {
    fun dbmToMw(dbm: Double): Double = 10.0.pow(dbm / 10)
    fun mwToDbm(mw: Double): Double = 10 * log10(mw)
    fun mbpsToMegabytesPerSecond(mbps: Double): Double = mbps / 8

    /** Seconds to move [sizeGb] (decimal gigabytes) at [mbps]. */
    fun transferSeconds(sizeGb: Double, mbps: Double): Double = if (mbps <= 0) Double.POSITIVE_INFINITY else sizeGb * 8000 / mbps
}

// endregion

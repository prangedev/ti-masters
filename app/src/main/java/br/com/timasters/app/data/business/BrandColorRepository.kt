package br.com.timasters.app.data.business

import br.com.timasters.app.data.plan.EntitlementRepository
import br.com.timasters.app.data.plan.Feature
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The company's brand color for the app theme; only Business accounts use their own colors. */
@Singleton
class BrandColorRepository @Inject constructor(
    companyRepository: CompanyRepository,
    companyProfileDao: CompanyProfileDao,
    entitlementRepository: EntitlementRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val companyColor: Flow<String?> = companyRepository.companyId.flatMapLatest { id ->
        if (id == null) flowOf(null) else companyProfileDao.observe(id).map { it?.primaryColor }
    }

    /** ARGB color, or null for the TI Masters colors. */
    val brandColor: Flow<Int?> = combine(entitlementRepository.entitlement, companyColor) { entitlement, hex ->
        hex?.takeIf { entitlement.plan.has(Feature.BUSINESS_SUITE) }?.let(::parseHexColor)
    }.distinctUntilChanged()
}

/** "#RRGGBB" → opaque ARGB int; null when malformed. */
fun parseHexColor(hex: String): Int? {
    val digits = hex.removePrefix("#")
    if (digits.length != 6) return null
    return digits.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
}

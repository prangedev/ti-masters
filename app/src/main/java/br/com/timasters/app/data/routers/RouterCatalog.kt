package br.com.timasters.app.data.routers

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Offline router database (assets/routers): brands, models with factory logins and where to get
 * firmware, plus step-by-step setup guides. Shipped with the app so it works without internet.
 */

@Serializable
data class RouterCatalogFile(val version: Int, val updated: String = "", val brands: List<RouterBrand>)

@Serializable
data class RouterBrand(
    val id: String,
    val name: String,
    val keywords: List<String> = emptyList(),
    val firmwareUrl: String = "",
    /** Model page pattern with "{path}" replaced by [RouterModel.firmwarePath]. */
    val firmwareModelUrl: String? = null,
    val notes: String = "",
    /** Download pages for updates and tools for restoring a bricked device. */
    val firmware: List<FirmwareRef> = emptyList(),
    /** Reset and recovery procedures that work for every model of the brand. */
    val recovery: List<GuideSection> = emptyList(),
    val models: List<RouterModel> = emptyList()
)

@Serializable
data class RouterModel(
    val name: String,
    val type: String = "roteador",
    val match: List<String> = emptyList(),
    val address: String = "",
    val logins: List<RouterLogin> = emptyList(),
    val notes: String = "",
    val firmwarePath: String? = null,
    /** "gpon", "epon" or "xpon" (both) for fiber equipment. */
    val pon: String? = null,
    /** Ids of related guides in guides.json. */
    val guides: List<String> = emptyList(),
    /** Step-by-step instructions specific to this model. */
    val setup: List<GuideSection> = emptyList()
)

@Serializable
data class RouterLogin(
    val role: String,
    val user: String,
    val password: String,
    /** Where the value comes from: "manual" (manufacturer), "etiqueta" (device label), "relatado" (reported by technicians). */
    val source: String = LoginSource.REPORTED
)

object LoginSource {
    const val MANUAL = "manual"
    const val LABEL = "etiqueta"
    const val REPORTED = "relatado"
}

@Serializable
data class FirmwareRef(
    /** "update" or "restore". */
    val kind: String,
    val title: String,
    val url: String? = null,
    val notes: String = ""
) {
    val isRestore: Boolean get() = kind == "restore"
}

@Serializable
data class RouterGuidesFile(val version: Int, val guides: List<RouterGuide>)

@Serializable
data class RouterGuide(
    val id: String,
    val title: String,
    val summary: String = "",
    /** Fiber (GPON/EPON) guides are Business-only. */
    val fiber: Boolean = false,
    val sections: List<GuideSection>
)

@Serializable
data class GuideSection(
    val title: String,
    val steps: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val tip: String? = null
)

/** A model together with its brand (what lists and the detail screen show). */
data class RouterEntry(val brand: RouterBrand, val modelIndex: Int) {
    val model: RouterModel get() = brand.models[modelIndex]
    val key: String get() = "${brand.id}#$modelIndex"

    /** Model download page when the brand has one, otherwise the brand's download page. */
    val firmwareUrl: String?
        get() {
            val path = model.firmwarePath
            val pattern = brand.firmwareModelUrl
            return if (path != null && pattern != null) pattern.replace("{path}", path) else brand.firmwareUrl.ifBlank { null }
        }

    /** ONT/ONU and anything with a PON type: shown only to Business accounts. */
    val isFiber: Boolean get() = model.pon != null || model.type in FIBER_TYPES

    /** The model's own download page first, then the brand's update and restore references. */
    val firmwareRefs: List<FirmwareRef>
        get() {
            val modelPage = firmwareUrl?.takeIf { model.firmwarePath != null }?.let { FirmwareRef("update", model.name, it) }
            return (listOfNotNull(modelPage) + brand.firmware).distinctBy { it.url ?: it.title }
        }

    companion object {
        val FIBER_TYPES = setOf("ont", "onu")
    }
}

data class RouterMatch(val brand: RouterBrand, val entry: RouterEntry?)

object RouterMatcher {

    /**
     * Finds the brand (and, when possible, the model) mentioned in what the router revealed about
     * itself (page title, server header, UPnP description...). Tokens must match whole words, so
     * short model names like "f3" or "hex" don't match inside other words.
     */
    fun match(brands: List<RouterBrand>, texts: List<String?>): RouterMatch? {
        val haystack = texts.filterNotNull().joinToString(" | ").lowercase()
        if (haystack.isBlank()) return null
        val brand = brands
            .map { brand -> brand to brand.keywords.filter { containsWord(haystack, it) }.maxOfOrNull { it.length } }
            .filter { it.second != null }
            .maxByOrNull { it.second!! }
            ?.first
            ?: return null
        val entry = brand.models.indices
            .map { index -> index to brand.models[index].match.filter { containsWord(haystack, it) }.maxOfOrNull { it.length } }
            .filter { it.second != null }
            .maxByOrNull { it.second!! }
            ?.let { RouterEntry(brand, it.first) }
        return RouterMatch(brand, entry)
    }

    fun search(brands: List<RouterBrand>, query: String): List<RouterEntry> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return brands.flatMap { brand -> brand.models.indices.map { RouterEntry(brand, it) } }
            .filter { entry ->
                val text = (listOfNotNull(entry.brand.name, entry.model.name, entry.model.type, entry.model.pon) + entry.brand.keywords + entry.model.match)
                    .joinToString(" ")
                    .lowercase()
                words.all { it in text }
            }
    }

    /**
     * Whole-word match. Model codes with digits (4+ chars) also match their variants:
     * "hg8245" finds "HG8245H5" and "f670" finds "F670L".
     */
    private fun containsWord(haystack: String, token: String): Boolean {
        val needle = token.lowercase()
        val isModelCode = needle.length >= 4 && needle.any { it.isDigit() }
        val end = if (isModelCode) "" else "($|[^a-z0-9])"
        return Regex("(^|[^a-z0-9])" + Regex.escape(needle) + end).containsMatchIn(haystack)
    }
}

@Singleton
class RouterCatalogRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val json: Json
) {
    private val mutex = Mutex()
    private var catalog: RouterCatalogFile? = null
    private var guides: RouterGuidesFile? = null

    suspend fun brands(): List<RouterBrand> = mutex.withLock {
        catalog ?: read<RouterCatalogFile>("routers/catalog.json").also { catalog = it }
    }.brands

    suspend fun guides(): List<RouterGuide> = mutex.withLock {
        guides ?: read<RouterGuidesFile>("routers/guides.json").also { guides = it }
    }.guides

    suspend fun entry(key: String): RouterEntry? {
        val brandId = key.substringBefore('#')
        val index = key.substringAfter('#').toIntOrNull() ?: return null
        val brand = brands().firstOrNull { it.id == brandId } ?: return null
        return brand.models.getOrNull(index)?.let { RouterEntry(brand, index) }
    }

    private suspend inline fun <reified T> read(path: String): T = withContext(Dispatchers.IO) {
        context.assets.open(path).bufferedReader().use { json.decodeFromString<T>(it.readText()) }
    }
}

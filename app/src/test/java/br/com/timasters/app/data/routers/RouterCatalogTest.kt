package br.com.timasters.app.data.routers

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the shipped router database (assets/routers) and the brand/model matching. */
class RouterCatalogTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val assets = File("src/main/assets/routers")
    private val brands: List<RouterBrand> by lazy {
        json.decodeFromString<RouterCatalogFile>(File(assets, "catalog.json").readText()).brands
    }

    @Test
    fun catalog_isValidAndComplete() {
        assertTrue(brands.isNotEmpty())
        assertEquals("brand ids must be unique", brands.size, brands.map { it.id }.toSet().size)
        brands.forEach { brand ->
            assertTrue("${brand.id} needs keywords", brand.keywords.isNotEmpty())
            brand.models.forEach { model ->
                assertTrue("${brand.id}/${model.name} needs logins", model.logins.isNotEmpty())
                assertTrue("${brand.id}/${model.name} needs an address", model.address.isNotBlank())
            }
            brand.firmwareModelUrl?.let { assertTrue("${brand.id} pattern needs {path}", "{path}" in it) }
        }
    }

    @Test
    fun guides_areValidAndIncludeScreenLinks() {
        val guides = json.decodeFromString<RouterGuidesFile>(File(assets, "guides.json").readText()).guides
        val ids = guides.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // The Routers screen opens these two guides directly.
        assertTrue("mikrotik" in ids)
        assertTrue("firmware" in ids)
        guides.forEach { guide -> assertTrue("${guide.id} needs sections", guide.sections.isNotEmpty()) }
    }

    @Test
    fun catalog_fiberModelsAreTaggedAndLinkExistingGuides() {
        val guides = json.decodeFromString<RouterGuidesFile>(File(assets, "guides.json").readText()).guides
        val guideIds = guides.map { it.id }.toSet()
        val entries = RouterMatcher.search(brands, "")
        val fiber = entries.filter { it.isFiber }

        assertTrue("catalog needs ONT/ONU models", fiber.size >= 10)
        fiber.forEach { entry ->
            assertTrue("${entry.key} needs a PON type", entry.model.pon in setOf("gpon", "epon", "xpon"))
            assertTrue("${entry.key} must be ont or onu", entry.model.type in RouterEntry.FIBER_TYPES)
        }
        entries.forEach { entry ->
            entry.model.guides.forEach { assertTrue("${entry.key} links unknown guide $it", it in guideIds) }
            entry.model.logins.forEach {
                assertTrue("${entry.key} login source", it.source in setOf(LoginSource.MANUAL, LoginSource.LABEL, LoginSource.REPORTED))
            }
        }
        // Fiber guides are flagged so the app can lock them outside Business.
        assertTrue(guides.filter { it.fiber }.map { it.id }.containsAll(listOf("gpon-epon", "fibra-sinal", "ont-pppoe", "ont-bridge")))
    }

    @Test
    fun catalog_firmwareRefsHaveValidKindsAndHttpsLinks() {
        brands.forEach { brand ->
            (brand.firmware).forEach { ref ->
                assertTrue("${brand.id} kind ${ref.kind}", ref.kind in setOf("update", "restore"))
                ref.url?.let { assertTrue("${brand.id} url $it", it.startsWith("https://")) }
            }
        }
        val mikrotik = RouterEntry(brands.first { it.id == "mikrotik" }, 0)
        assertTrue(mikrotik.firmwareRefs.any { it.isRestore })
    }

    @Test
    fun match_findsFiberModelFromUpnp() {
        val match = RouterMatcher.match(brands, listOf("Huawei Technologies", "HG8245H5"))!!

        assertEquals("huawei", match.brand.id)
        assertTrue(match.entry!!.isFiber)
        assertEquals("zte", RouterMatcher.match(brands, listOf("ZTE", "F670L"))!!.entry!!.brand.id)
        assertTrue(RouterMatcher.match(brands, listOf("V2801F"))!!.entry!!.isFiber)
    }

    @Test
    fun match_findsBrandAndModelFromRealm() {
        val match = RouterMatcher.match(brands, listOf("TP-LINK Wireless N Router WR841N"))!!

        assertEquals("tplink", match.brand.id)
        assertNotNull(match.entry)
        assertTrue("wr841n" in match.entry!!.model.match)
    }

    @Test
    fun match_requiresWholeWords() {
        // "archer" is a TP-Link keyword, but "searcher" must not trigger it.
        assertNull(RouterMatcher.match(brands, listOf("searcher web server")))
        assertNull(RouterMatcher.match(brands, listOf(null, "")))
    }

    @Test
    fun search_filtersByEveryWord() {
        val all = RouterMatcher.search(brands, "")
        assertEquals(brands.sumOf { it.models.size }, all.size)

        val tplink = RouterMatcher.search(brands, "tp-link archer")
        assertTrue(tplink.isNotEmpty())
        assertTrue(tplink.all { it.brand.id == "tplink" })
        assertTrue(RouterMatcher.search(brands, "marca-que-nao-existe").isEmpty())
    }

    @Test
    fun entry_buildsModelFirmwareUrl() {
        val tplink = brands.first { it.id == "tplink" }
        val index = tplink.models.indexOfFirst { it.firmwarePath != null }
        val entry = RouterEntry(tplink, index)

        assertEquals("tplink#$index", entry.key)
        assertEquals(tplink.firmwareModelUrl!!.replace("{path}", tplink.models[index].firmwarePath!!), entry.firmwareUrl)
    }
}

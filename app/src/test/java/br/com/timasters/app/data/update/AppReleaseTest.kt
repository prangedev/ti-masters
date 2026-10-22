package br.com.timasters.app.data.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseTest {

    private val release = AppRelease(
        versionCode = 10200,
        versionName = "1.2.0",
        apkPath = "v10200/TI-Masters-1.2.0.apk",
        sha256 = "a".repeat(64),
        sizeBytes = 31_000_000,
        minSupportedCode = 10100
    )

    @Test
    fun newerAndMandatory() {
        assertTrue(release.isNewerThan(10100))
        assertFalse(release.isNewerThan(10200))
        assertTrue(release.isMandatoryFor(10000))
        assertFalse(release.isMandatoryFor(10100))
    }

    @Test
    fun postponedDialogStaysHiddenUnlessMandatory() {
        val postponed = UpdateState(available = release, postponedVersionCode = 10200)
        assertFalse(postponed.showDialog(installedCode = 10100))
        assertTrue(postponed.showDialog(installedCode = 1))
        assertTrue(UpdateState(available = release).showDialog(installedCode = 10100))
        assertFalse(UpdateState().showDialog(installedCode = 10100))
    }

    @Test
    fun decodesServerRow() {
        val row = """{"version_code":10100,"version_name":"1.1.0","notes":"- Novo","apk_path":"v10100/x.apk",
            "sha256":"${"b".repeat(64)}","size_bytes":123,"min_supported_code":0,"published_at":"2026-10-22T10:00:00Z"}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<AppRelease>(row)
        assertEquals(10100, decoded.versionCode)
        assertEquals("1.1.0", decoded.versionName)
        assertEquals(123L, decoded.sizeBytes)
    }
}

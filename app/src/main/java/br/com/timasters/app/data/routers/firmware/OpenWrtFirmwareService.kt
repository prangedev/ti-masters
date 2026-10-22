package br.com.timasters.app.data.routers.firmware

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Where images come from; an interface so the flashing flow can be tested without internet. */
interface FirmwareSource {
    suspend fun stableVersion(): String
    suspend fun overview(version: String): List<OverviewProfile>
    suspend fun profiles(version: String, target: String): TargetProfiles

    /** [defaults] = first-boot script, or null to keep the image as built. */
    suspend fun submit(version: String, target: String, profile: String, packages: List<String>, defaults: String?): BuildStatus
    suspend fun status(hash: String): BuildStatus
    suspend fun download(binDir: String, image: FirmwareImage, onProgress: (Float) -> Unit): VerifiedImage
}

class ChecksumMismatchException : IOException("SHA-256 mismatch")

/**
 * OpenWrt's official services: release metadata from downloads.openwrt.org and custom builds from
 * the Attended SysUpgrade server (the same one behind firmware-selector.openwrt.org).
 */
@Singleton
class OpenWrtFirmwareService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) : FirmwareSource {

    private val mutex = Mutex()
    private val overviewCache = mutableMapOf<String, List<OverviewProfile>>()

    override suspend fun stableVersion(): String =
        getJson("$DOWNLOADS/.versions.json").jsonObject["stable_version"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("No stable version")

    override suspend fun overview(version: String): List<OverviewProfile> = mutex.withLock {
        overviewCache[version] ?: json.decodeFromJsonElement<OverviewFile>(getJson("$DOWNLOADS/releases/$version/.overview.json"))
            .profiles.also { overviewCache[version] = it }
    }

    override suspend fun profiles(version: String, target: String): TargetProfiles =
        json.decodeFromJsonElement(getJson("$DOWNLOADS/releases/$version/targets/$target/profiles.json"))

    override suspend fun submit(version: String, target: String, profile: String, packages: List<String>, defaults: String?): BuildStatus {
        val body = buildJsonObject {
            put("version", version)
            put("target", target)
            put("profile", profile)
            put("packages", JsonArray(packages.map(::JsonPrimitive)))
            put("diff_packages", false)
            put("client", CLIENT)
            defaults?.let { put("defaults", it) }
        }
        val request = Request.Builder().url("$ASU/api/v1/build").post(body.toString().toRequestBody(JSON_TYPE)).build()
        return call(request)
    }

    override suspend fun status(hash: String): BuildStatus =
        call(Request.Builder().url("$ASU/api/v1/build/$hash").get().build())

    override suspend fun download(binDir: String, image: FirmwareImage, onProgress: (Float) -> Unit): VerifiedImage =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$ASU/store/$binDir/${image.name}").get().build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val total = response.body.contentLength().takeIf { it > 0 } ?: image.size
                if (total > MAX_IMAGE_BYTES) throw IOException("Image too large")
                val digest = MessageDigest.getInstance("SHA-256")
                val out = ByteArrayOutputStream(total.toInt().coerceAtLeast(0))
                val buffer = ByteArray(64 * 1024)
                response.body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        if (out.size() > MAX_IMAGE_BYTES) throw IOException("Image too large")
                        if (total > 0) onProgress(out.size().toFloat() / total)
                    }
                }
                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (image.sha256.isNotBlank() && !sha256.equals(image.sha256, ignoreCase = true)) throw ChecksumMismatchException()
                VerifiedImage(image.name, sha256, out.toByteArray())
            }
        }

    private suspend fun getJson(url: String) = withContext(Dispatchers.IO) {
        okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            json.parseToJsonElement(response.body.string())
        }
    }

    private suspend fun call(request: Request): BuildStatus = withContext(Dispatchers.IO) {
        okHttpClient.newCall(request).execute().use { response -> parseBuild(response.code, response.body.string(), json) }
    }

    companion object {
        const val DOWNLOADS = "https://downloads.openwrt.org"
        const val ASU = "https://sysupgrade.openwrt.org"
        private const val CLIENT = "ti-masters-android/1.0"
        private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
        private val JSON_TYPE = "application/json".toMediaType()

        /** ASU answers: 200 = done (with images), 202 = queued/building, anything else = error with "detail". */
        fun parseBuild(code: Int, body: String, json: Json): BuildStatus {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return BuildStatus.Failed("HTTP $code")
            fun text(key: String) = (root[key] as? JsonPrimitive)?.contentOrNull
            val hash = text("request_hash").orEmpty()
            return when (code) {
                200 -> {
                    val images = (root["images"] as? JsonArray)?.map { json.decodeFromJsonElement<FirmwareImage>(it) }.orEmpty()
                    BuildStatus.Done(hash, text("bin_dir") ?: hash, images)
                }
                202 -> if (text("detail") == "queued") {
                    BuildStatus.Queued(hash, (root["queue_position"] as? JsonPrimitive)?.intOrNull)
                } else {
                    BuildStatus.Building(hash)
                }
                else -> BuildStatus.Failed(text("detail") ?: "HTTP $code")
            }
        }
    }
}

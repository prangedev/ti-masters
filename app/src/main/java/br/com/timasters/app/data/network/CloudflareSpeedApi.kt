package br.com.timasters.app.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Public endpoints behind speed.cloudflare.com: nearest data center, no account or key needed. */
object CloudflareSpeedEndpoints {
    private const val BASE = "https://speed.cloudflare.com"
    const val TRACE = "$BASE/cdn-cgi/trace"
    const val META = "$BASE/meta"
    const val UPLOAD = "$BASE/__up"
    fun download(bytes: Long) = "$BASE/__down?bytes=$bytes"
}

data class PublicNetworkInfo(
    val clientIp: String? = null,
    val asOrganization: String? = null,
    val city: String? = null,
    val country: String? = null,
    /** IATA code of the Cloudflare data center serving the test, e.g. "GRU". */
    val colo: String? = null
)

@Singleton
class CloudflareSpeedApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json
) {
    /**
     * Public IP and test server from /cdn-cgi/trace (stable plain-text format), enriched with the
     * provider name and city from /meta when available (that endpoint is not a public contract).
     */
    suspend fun fetchPublicInfo(): PublicNetworkInfo = withContext(Dispatchers.IO) {
        val trace = parseTrace(get(CloudflareSpeedEndpoints.TRACE))
        val base = PublicNetworkInfo(clientIp = trace["ip"], colo = trace["colo"], country = trace["loc"])
        try {
            val meta = parseMeta(get(CloudflareSpeedEndpoints.META, referer = "https://speed.cloudflare.com/"))
            base.copy(
                clientIp = meta.clientIp ?: base.clientIp,
                asOrganization = meta.asOrganization,
                city = meta.city,
                colo = meta.colo ?: base.colo
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            base
        }
    }

    private fun get(url: String, referer: String? = null): String {
        val request = Request.Builder().url(url).apply { if (referer != null) header("Referer", referer) }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body.string()
        }
    }

    private fun parseMeta(body: String): PublicNetworkInfo {
        val root = json.parseToJsonElement(body).jsonObject
        fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        // "colo" has been both a plain code and an object with "iata"; accept either.
        val colo = when (val value = root["colo"]) {
            is JsonPrimitive -> value.content
            is JsonObject -> value["iata"]?.jsonPrimitive?.content
            else -> null
        }
        return PublicNetworkInfo(
            clientIp = root.text("clientIp"),
            asOrganization = root.text("asOrganization"),
            city = root.text("city"),
            country = root.text("country"),
            colo = colo
        )
    }

    companion object {
        /** Parses "key=value" lines, e.g. "ip=203.0.113.7". */
        fun parseTrace(body: String): Map<String, String> = body.lineSequence()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { (key, value) -> key.trim() to value.trim() }
    }
}

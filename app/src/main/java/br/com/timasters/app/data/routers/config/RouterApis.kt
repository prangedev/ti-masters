package br.com.timasters.app.data.routers.config

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

/** Wrong user or password. */
class RouterAuthException : IOException("Authentication failed")

/** The router answered with an error; [detail] is its own message (shown to the technician). */
class RouterApiException(val detail: String) : IOException(detail)

/** RouterOS menus as maps of strings, the way the REST API returns them. */
interface RouterOsClient {
    suspend fun list(path: String): List<Map<String, String>>

    /** Menus without items, e.g. system/resource or interface/wifi/capsman. */
    suspend fun get(path: String): Map<String, String>

    /** Returns the new item's .id. */
    suspend fun add(path: String, values: Map<String, String>): String
    suspend fun set(path: String, id: String, values: Map<String, String>)
    suspend fun remove(path: String, id: String)
    suspend fun setSingleton(path: String, values: Map<String, String>)
    suspend fun backup(name: String)

    /** Commands such as system/package/update/install or system/reboot (REST POST). */
    suspend fun run(path: String, values: Map<String, String> = emptyMap())
}

/** Result of OpenWrt's own image check (board, metadata and signature). */
data class FirmwareCheck(val valid: Boolean, val allowKeepSettings: Boolean)

/** Checksums cgi-upload reports for the received file (older versions report only md5). */
data class UploadReceipt(val sha256: String?, val md5: String?)

/** OpenWrt system calls used to flash a new image. */
interface OpenWrtSystemClient {
    suspend fun board(): Map<String, JsonElement>

    /** Free RAM in bytes; the image is uploaded to /tmp, which lives in RAM. */
    suspend fun availableMemory(): Long?

    /** Packages the user installed on top of the image defaults, to keep them in the new build; null = couldn't read. */
    suspend fun userPackages(): List<String>?

    /** Uploads to [path]; returns the checksums the router computed for what it received. */
    suspend fun upload(path: String, data: ByteArray): UploadReceipt
    suspend fun md5(path: String): String?
    suspend fun validateFirmware(path: String): FirmwareCheck
    suspend fun startUpgrade(keepSettings: Boolean)
}

/** OpenWrt UCI through ubus. Changes are staged until [applyWithRollback] + [confirm]. */
interface UciClient {
    /** Sections by name; each map includes ".type" and ".anonymous". */
    suspend fun get(config: String): Map<String, Map<String, JsonElement>>

    /** Returns the new section's name. */
    suspend fun add(config: String, type: String, values: Map<String, JsonElement>): String
    suspend fun set(config: String, section: String, values: Map<String, JsonElement>)
    suspend fun deleteOptions(config: String, section: String, options: List<String>)
    suspend fun deleteSection(config: String, section: String)
    suspend fun revert(config: String)

    /** Applies staged changes; the router undoes them by itself unless [confirm] arrives in time. */
    suspend fun applyWithRollback(timeoutSeconds: Int)
    suspend fun confirm()
    suspend fun board(): Map<String, JsonElement>
}

/** MikroTik RouterOS 7 REST API (/rest) with basic authentication. */
class MikroTikRestClient(
    private val endpoint: RouterEndpoint,
    user: String,
    password: String,
    private val json: Json
) : RouterOsClient {

    private val auth = "Basic " + Base64.encodeToString("$user:$password".toByteArray(), Base64.NO_WRAP)

    override suspend fun list(path: String): List<Map<String, String>> =
        call("GET", path).jsonArray.map { it.jsonObject.toStrings() }

    override suspend fun get(path: String): Map<String, String> = call("GET", path).jsonObject.toStrings()

    override suspend fun add(path: String, values: Map<String, String>): String =
        call("PUT", path, values).jsonObject[".id"]?.jsonPrimitive?.contentOrNull ?: throw RouterApiException("No .id returned")

    override suspend fun set(path: String, id: String, values: Map<String, String>) {
        call("PATCH", "$path/$id", values)
    }

    override suspend fun remove(path: String, id: String) {
        call("DELETE", "$path/$id")
    }

    override suspend fun setSingleton(path: String, values: Map<String, String>) {
        call("POST", "$path/set", values)
    }

    override suspend fun backup(name: String) {
        call("POST", "system/backup/save", mapOf("name" to name, "dont-encrypt" to "yes"))
    }

    override suspend fun run(path: String, values: Map<String, String>) {
        call("POST", path, values)
    }

    private suspend fun call(method: String, path: String, body: Map<String, String>? = null): JsonElement {
        val response = LanHttpClient.request(
            endpoint = endpoint,
            method = method,
            path = "/rest/$path",
            headers = mapOf("Authorization" to auth),
            body = body?.let { values -> buildJsonObject { values.forEach { (key, value) -> put(key, value) } }.toString() }
        )
        if (response.status == 401) throw RouterAuthException()
        val element = response.body.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        if (response.status >= 400) {
            val detail = (element as? JsonObject)?.let { it["detail"] ?: it["message"] }?.jsonPrimitive?.contentOrNull
            throw LanHttpException(response.status, detail ?: "HTTP ${response.status}")
        }
        return element ?: JsonObject(emptyMap())
    }

    private fun JsonObject.toStrings(): Map<String, String> =
        mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull ?: value.toString() }
}

/** OpenWrt ubus JSON-RPC (/ubus), served by uhttpd when LuCI is installed. */
class OpenWrtUbusClient private constructor(
    private val endpoint: RouterEndpoint,
    private val json: Json,
    private val session: String
) : UciClient, OpenWrtSystemClient {

    override suspend fun get(config: String): Map<String, Map<String, JsonElement>> {
        val values = call(session, json, endpoint, "uci", "get", buildJsonObject { put("config", config) })["values"]?.jsonObject
            ?: return emptyMap()
        return values.mapValues { (_, section) -> section.jsonObject }
    }

    override suspend fun add(config: String, type: String, values: Map<String, JsonElement>): String =
        call(session, json, endpoint, "uci", "add", buildJsonObject {
            put("config", config)
            put("type", type)
            put("values", JsonObject(values))
        })["section"]?.jsonPrimitive?.contentOrNull ?: throw RouterApiException("uci add returned no section")

    override suspend fun set(config: String, section: String, values: Map<String, JsonElement>) {
        call(session, json, endpoint, "uci", "set", buildJsonObject {
            put("config", config)
            put("section", section)
            put("values", JsonObject(values))
        })
    }

    override suspend fun deleteOptions(config: String, section: String, options: List<String>) {
        call(session, json, endpoint, "uci", "delete", buildJsonObject {
            put("config", config)
            put("section", section)
            put("options", buildJsonArray { options.forEach { add(JsonPrimitive(it)) } })
        })
    }

    override suspend fun deleteSection(config: String, section: String) {
        call(session, json, endpoint, "uci", "delete", buildJsonObject {
            put("config", config)
            put("section", section)
        })
    }

    override suspend fun revert(config: String) {
        call(session, json, endpoint, "uci", "revert", buildJsonObject { put("config", config) })
    }

    override suspend fun applyWithRollback(timeoutSeconds: Int) {
        call(session, json, endpoint, "uci", "apply", buildJsonObject {
            put("rollback", true)
            put("timeout", timeoutSeconds)
        })
    }

    override suspend fun confirm() {
        call(session, json, endpoint, "uci", "confirm", JsonObject(emptyMap()))
    }

    override suspend fun board(): Map<String, JsonElement> =
        call(session, json, endpoint, "system", "board", JsonObject(emptyMap()))

    // region Firmware (same calls LuCI's "Backup / Flash firmware" page uses)

    override suspend fun availableMemory(): Long? {
        val memory = call(session, json, endpoint, "system", "info", JsonObject(emptyMap()))["memory"] as? JsonObject ?: return null
        fun value(key: String) = memory[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return value("available") ?: value("free")?.let { it + (value("buffered") ?: 0) }
    }

    override suspend fun userPackages(): List<String>? = runCatching {
        val packages = call(session, json, endpoint, "rpc-sys", "packagelist", buildJsonObject { put("all", false) })["packages"] as? JsonObject
        packages?.keys?.toList()
    }.getOrNull()

    /** Upload through cgi-upload (multipart), the same endpoint LuCI uses. */
    override suspend fun upload(path: String, data: ByteArray): UploadReceipt {
        val boundary = "----TIMasters${System.nanoTime()}"
        fun field(name: String, value: String) =
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
        val head = field("sessionid", session) + field("filename", path) + field("filemode", "0600") +
            "--$boundary\r\nContent-Disposition: form-data; name=\"filedata\"; filename=\"firmware.bin\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val body = head.toByteArray(Charsets.UTF_8) + data + tail.toByteArray(Charsets.UTF_8)
        val response = LanHttpClient.request(
            endpoint = endpoint,
            method = "POST",
            path = "/cgi-bin/cgi-upload",
            binary = body,
            contentType = "multipart/form-data; boundary=$boundary"
        )
        if (response.status == 403) throw RouterAuthException()
        if (response.status >= 400) throw LanHttpException(response.status, "upload: HTTP ${response.status}")
        val result = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        fun text(key: String) = result?.get(key)?.jsonPrimitive?.contentOrNull
        return UploadReceipt(sha256 = text("sha256sum"), md5 = text("checksum"))
    }

    override suspend fun md5(path: String): String? = runCatching {
        call(session, json, endpoint, "file", "md5", buildJsonObject { put("path", path) })["md5"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    override suspend fun validateFirmware(path: String): FirmwareCheck {
        val result = call(session, json, endpoint, "system", "validate_firmware_image", buildJsonObject { put("path", path) })
        fun flag(key: String) = result[key]?.jsonPrimitive?.contentOrNull == "true"
        return FirmwareCheck(valid = flag("valid"), allowKeepSettings = flag("allow_backup"))
    }

    override suspend fun startUpgrade(keepSettings: Boolean) {
        try {
            call(session, json, endpoint, "rpc-sys", "upgrade_start", buildJsonObject { put("keep", keepSettings) })
        } catch (e: RouterAuthException) {
            throw e
        } catch (e: RouterApiException) {
            throw e
        } catch (_: IOException) {
            // The router stops answering once it starts writing the flash; a dropped connection is expected.
        }
    }

    // endregion

    companion object {
        private const val NO_SESSION = "00000000000000000000000000000000"
        private const val UBUS_PERMISSION_DENIED = 6

        suspend fun login(endpoint: RouterEndpoint, user: String, password: String, json: Json): OpenWrtUbusClient {
            val result = call(NO_SESSION, json, endpoint, "session", "login", buildJsonObject {
                put("username", user)
                put("password", password)
            })
            val session = result["ubus_rpc_session"]?.jsonPrimitive?.contentOrNull ?: throw RouterAuthException()
            return OpenWrtUbusClient(endpoint, json, session)
        }

        private suspend fun call(
            session: String,
            json: Json,
            endpoint: RouterEndpoint,
            obj: String,
            method: String,
            params: JsonObject
        ): JsonObject {
            val request = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "call")
                put("params", buildJsonArray {
                    add(JsonPrimitive(session))
                    add(JsonPrimitive(obj))
                    add(JsonPrimitive(method))
                    add(params)
                })
            }
            val response = LanHttpClient.request(endpoint, "POST", "/ubus", body = request.toString())
            if (response.status >= 400) throw LanHttpException(response.status, "HTTP ${response.status}")
            val root = json.parseToJsonElement(response.body).jsonObject
            root["error"]?.let { error ->
                val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (message.contains("Access denied", ignoreCase = true)) throw RouterAuthException()
                throw RouterApiException(message.ifBlank { "ubus error" })
            }
            val result = root["result"] as? JsonArray ?: throw RouterApiException("ubus: empty result")
            val code = result.firstOrNull()?.jsonPrimitive?.int ?: -1
            if (code == UBUS_PERMISSION_DENIED) throw RouterAuthException()
            if (code != 0) throw RouterApiException("ubus $obj.$method: code $code")
            return result.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())
        }
    }
}

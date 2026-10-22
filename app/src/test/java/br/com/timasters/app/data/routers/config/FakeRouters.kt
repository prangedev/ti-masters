package br.com.timasters.app.data.routers.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/** In-memory RouterOS: menus of items with auto ".id", like the REST API. */
class FakeRouterOs(initial: Map<String, List<Map<String, String>>> = emptyMap()) : RouterOsClient {

    val menus: MutableMap<String, MutableList<MutableMap<String, String>>> =
        initial.mapValues { (_, items) -> items.map { it.toMutableMap() }.toMutableList() }.toMutableMap()
    val singletons = mutableMapOf<String, MutableMap<String, String>>()
    val backups = mutableListOf<String>()

    /** Make adding to this path fail, to test rollback. */
    var failAddOn: String? = null
    private var nextId = 100

    init {
        menus.values.flatten().forEach { item -> if (".id" !in item) item[".id"] = "*${nextId++}" }
    }

    fun state(): Map<String, List<Map<String, String>>> = menus.mapValues { (_, items) -> items.map { it.toMap() } } +
        singletons.mapKeys { "single:${it.key}" }.mapValues { listOf(it.value.toMap()) }

    override suspend fun list(path: String) = menus[path].orEmpty().map { it.toMap() }

    override suspend fun get(path: String): Map<String, String> =
        singletons[path]?.toMap() ?: throw LanHttpException(404, "no such command")

    override suspend fun add(path: String, values: Map<String, String>): String {
        if (path == failAddOn) throw LanHttpException(400, "failure: simulated")
        val id = "*${nextId++}"
        menus.getOrPut(path) { mutableListOf() } += (values + (".id" to id)).toMutableMap()
        return id
    }

    override suspend fun set(path: String, id: String, values: Map<String, String>) {
        val item = menus[path]?.firstOrNull { it[".id"] == id } ?: throw LanHttpException(404, "no such item")
        item.putAll(values)
    }

    override suspend fun remove(path: String, id: String) {
        if (menus[path]?.removeIf { it[".id"] == id } != true) throw LanHttpException(404, "no such item")
    }

    override suspend fun setSingleton(path: String, values: Map<String, String>) {
        singletons.getValue(path).putAll(values)
    }

    override suspend fun backup(name: String) {
        backups += name
    }

    /** Commands run, in order (e.g. "system/package/update/install"). */
    val commands = mutableListOf<String>()

    override suspend fun run(path: String, values: Map<String, String>) {
        commands += path
    }
}

/** In-memory UCI with staging: changes go live only after apply + confirm. */
class FakeUci(initial: Map<String, Map<String, Map<String, JsonElement>>>) : UciClient {

    var live: Map<String, Map<String, Map<String, JsonElement>>> = initial
        private set
    private var staged: MutableMap<String, MutableMap<String, MutableMap<String, JsonElement>>> = copy(initial)
    private var nextSection = 1
    var confirmWorks = true
    var applied = 0

    private fun copy(source: Map<String, Map<String, Map<String, JsonElement>>>) =
        source.mapValues { (_, sections) -> sections.mapValues { it.value.toMutableMap() }.toMutableMap() }.toMutableMap()

    override suspend fun get(config: String) = live[config].orEmpty()

    override suspend fun add(config: String, type: String, values: Map<String, JsonElement>): String {
        val name = "cfg${nextSection++}"
        staged.getOrPut(config) { mutableMapOf() }[name] = (values + (".type" to JsonPrimitive(type))).toMutableMap()
        return name
    }

    override suspend fun set(config: String, section: String, values: Map<String, JsonElement>) {
        staged[config]?.get(section)?.putAll(values) ?: throw IOException("no section $config.$section")
    }

    override suspend fun deleteOptions(config: String, section: String, options: List<String>) {
        options.forEach { staged[config]?.get(section)?.remove(it) }
    }

    override suspend fun deleteSection(config: String, section: String) {
        staged[config]?.remove(section)
    }

    override suspend fun revert(config: String) {
        staged[config] = copy(live)[config] ?: mutableMapOf()
    }

    override suspend fun applyWithRollback(timeoutSeconds: Int) {
        applied++
    }

    override suspend fun confirm() {
        if (!confirmWorks) throw IOException("timeout")
        live = staged.mapValues { (_, sections) -> sections.mapValues { it.value.toMap() } }
        staged = copy(live)
    }

    override suspend fun board(): Map<String, JsonElement> = mapOf("model" to JsonPrimitive("Fake Router"))
}

package br.com.timasters.app.data.routers.config

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/**
 * Applies a plan and reverts it. Every change is recorded with what's needed to undo it; if a step
 * fails, the steps already applied are undone right away so the router isn't left half-configured.
 */
interface RouterExecutor {
    suspend fun apply(plan: ConfigPlan): ApplyResult

    /** Reverts a previous successful [apply], newest change first. */
    suspend fun undo(applied: List<AppliedOp>): ApplyResult
}

class MikroTikExecutor(
    private val client: RouterOsClient,
    private val clock: () -> Long = System::currentTimeMillis
) : RouterExecutor {

    override suspend fun apply(plan: ConfigPlan): ApplyResult {
        // A RouterOS backup on the router itself, restorable from Files even without the app.
        val backupName = "timasters-${clock() / 1000}"
        try {
            client.backup(backupName)
        } catch (e: Exception) {
            return ApplyResult.Failed(e.message.orEmpty(), rolledBack = true)
        }
        val applied = mutableListOf<AppliedOp>()
        for (op in plan.ops) {
            try {
                applied += when (op) {
                    is RosAdd -> AppliedOp(op, client.add(op.path, op.values))
                    is RosSet -> AppliedOp(op).also { client.set(op.path, op.id, op.values) }
                    is RosSetSingleton -> AppliedOp(op).also { client.setSingleton(op.path, op.values) }
                    else -> error("Not a RouterOS change: $op")
                }
            } catch (e: Exception) {
                return ApplyResult.Failed(e.message.orEmpty(), rolledBack = revert(applied))
            }
        }
        return ApplyResult.Success(applied, backupName)
    }

    override suspend fun undo(applied: List<AppliedOp>): ApplyResult =
        if (revert(applied)) ApplyResult.Success(emptyList(), null) else ApplyResult.Failed("", rolledBack = false)

    private suspend fun revert(applied: List<AppliedOp>): Boolean {
        var ok = true
        applied.asReversed().forEach { (op, createdId) ->
            try {
                when (op) {
                    is RosAdd -> createdId?.let { client.remove(op.path, it) }
                    is RosSet -> op.previous.takeIf { it.isNotEmpty() }?.let { client.set(op.path, op.id, it) }
                    is RosSetSingleton -> op.previous.takeIf { it.isNotEmpty() }?.let { client.setSingleton(op.path, it) }
                    else -> Unit
                }
            } catch (_: Exception) {
                ok = false
            }
        }
        return ok
    }
}

/**
 * OpenWrt: changes are staged, then applied with the router's own rollback timer (the same safety
 * net LuCI uses). If the app can't confirm in time — for example because the change cut the
 * connection — the router restores the previous configuration by itself.
 */
class OpenWrtExecutor(
    private val client: UciClient,
    private val confirmAttempts: Int = 10,
    private val confirmDelayMs: Long = 3_000
) : RouterExecutor {

    override suspend fun apply(plan: ConfigPlan): ApplyResult {
        val applied = mutableListOf<AppliedOp>()
        val configs = plan.ops.map(::configOf).distinct()
        try {
            plan.ops.forEach { op ->
                applied += when (op) {
                    is UciAdd -> AppliedOp(op, client.add(op.config, op.type, op.values))
                    is UciSet -> AppliedOp(op).also { client.set(op.config, op.section, op.values) }
                    else -> error("Not a UCI change: $op")
                }
            }
        } catch (e: Exception) {
            configs.forEach { runCatching { client.revert(it) } }
            return ApplyResult.Failed(e.message.orEmpty(), rolledBack = true)
        }
        return commit(configs, applied)
    }

    override suspend fun undo(applied: List<AppliedOp>): ApplyResult {
        val configs = applied.map { configOf(it.op) }.distinct()
        try {
            applied.asReversed().forEach { (op, createdId) ->
                when (op) {
                    is UciAdd -> createdId?.let { client.deleteSection(op.config, it) }
                    is UciSet -> {
                        val restore = op.previous.filterValues { it != null }.mapValues { it.value as JsonElement }
                        val remove = op.previous.filterValues { it == null }.keys.toList()
                        if (restore.isNotEmpty()) client.set(op.config, op.section, restore)
                        if (remove.isNotEmpty()) client.deleteOptions(op.config, op.section, remove)
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            configs.forEach { runCatching { client.revert(it) } }
            return ApplyResult.Failed(e.message.orEmpty(), rolledBack = true)
        }
        return when (val result = commit(configs, emptyList())) {
            is ApplyResult.Success -> result
            is ApplyResult.Failed -> result.copy(rolledBack = false)
        }
    }

    private suspend fun commit(configs: List<String>, applied: List<AppliedOp>): ApplyResult {
        try {
            client.applyWithRollback(ROLLBACK_SECONDS)
        } catch (e: Exception) {
            configs.forEach { runCatching { client.revert(it) } }
            return ApplyResult.Failed(e.message.orEmpty(), rolledBack = true)
        }
        // Network changes restart interfaces; keep trying until the router answers again.
        repeat(confirmAttempts) {
            delay(confirmDelayMs)
            if (runCatching { client.confirm() }.isSuccess) return ApplyResult.Success(applied, null)
        }
        return ApplyResult.Failed(CONFIRM_TIMEOUT, rolledBack = true)
    }

    private fun configOf(op: ConfigOp): String = when (op) {
        is UciAdd -> op.config
        is UciSet -> op.config
        else -> error("Not a UCI change: $op")
    }

    companion object {
        const val ROLLBACK_SECONDS = 90
        const val CONFIRM_TIMEOUT = "confirm-timeout"
    }
}

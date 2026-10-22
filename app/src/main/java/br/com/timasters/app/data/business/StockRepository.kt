package br.com.timasters.app.data.business

import br.com.timasters.app.data.business.sync.BusinessSyncScheduler
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Pure stock rules, kept apart so they can be unit-tested. */
object StockLedger {
    /**
     * Quantity each stock-controlled product loses when [items] are used by a concluded
     * service order (negative numbers; one entry per product even if it appears twice).
     */
    fun orderConsumption(items: List<OrderItem>, trackedIds: Set<String>): Map<String, Double> =
        items.filter { it.catalogId != null && it.catalogId in trackedIds && it.quantity > 0 }
            .groupBy { it.catalogId!! }
            .mapValues { (_, lines) -> -lines.sumOf { it.quantity } }

    /** Movement that brings the stock from [current] to the [counted] quantity (null if equal). */
    fun adjustmentDelta(current: Double, counted: Double): Double? =
        (counted - current).takeIf { abs(it) >= 0.0005 }
}

/**
 * Parts stock. The quantity of a product is the sum of its movements: manual ones (entries,
 * withdrawals, counts) and the automatic ones of concluded service orders.
 */
@Singleton
class StockRepository @Inject constructor(
    private val movementDao: StockMovementDao,
    private val serviceItemDao: ServiceItemDao,
    private val syncScheduler: BusinessSyncScheduler
) {
    private fun now() = System.currentTimeMillis()

    fun levels(companyId: String): Flow<List<StockLevel>> = movementDao.observeLevels(companyId)
    fun level(itemId: String): Flow<StockLevel?> = movementDao.observeLevel(itemId)
    fun history(itemId: String): Flow<List<StockMovementWithOrder>> = movementDao.observeHistory(itemId)

    /** [quantity] is signed: positive adds, negative removes. */
    suspend fun addMovement(itemId: String, kind: StockMovementKind, quantity: Double, unitCostCents: Long, note: String) {
        val item = serviceItemDao.get(itemId) ?: return
        if (quantity == 0.0) return
        val time = now()
        movementDao.upsert(
            StockMovementEntity(
                id = UUID.randomUUID().toString(),
                companyId = item.companyId,
                itemId = itemId,
                kind = kind.serverId,
                quantity = quantity,
                unitCostCents = unitCostCents,
                note = note.trim().ifEmpty { null },
                createdAt = time,
                updatedAt = time
            )
        )
        syncScheduler.requestSync()
    }

    /** Stock count: records the difference between what is on the shelf and what the app shows. */
    suspend fun adjustTo(itemId: String, counted: Double, note: String) {
        val delta = StockLedger.adjustmentDelta(movementDao.quantity(itemId), counted) ?: return
        addMovement(itemId, StockMovementKind.ADJUSTMENT, delta, 0, note)
    }

    /** Undo of a manual movement; automatic ones follow their service order. */
    suspend fun deleteMovement(id: String) {
        val existing = movementDao.get(id) ?: return
        if (existing.movementKind == StockMovementKind.SERVICE_ORDER) return
        val time = now()
        movementDao.upsert(existing.copy(deletedAt = time, updatedAt = time, isDirty = true))
        syncScheduler.requestSync()
    }

    /**
     * Makes the automatic movements of [order] match it: a concluded service order removes its
     * parts from the stock; any other state (or a deleted order) gives them back.
     */
    suspend fun syncOrderConsumption(order: ServiceOrderEntity) {
        val concluded = order.orderKind == OrderKind.SERVICE_ORDER &&
            OrderStatus.from(order.status) == OrderStatus.DONE && order.deletedAt == null
        val items = order.items.mapNotNull { it.catalogId }.distinct()
            .mapNotNull { serviceItemDao.get(it) }
            .filter { it.trackStock && it.deletedAt == null }
            .associateBy { it.id }
        val wanted = if (concluded) StockLedger.orderConsumption(order.items, items.keys) else emptyMap()
        val existing = movementDao.ofOrder(order.id).associateBy { it.id }
        val time = now()
        var changed = false
        wanted.forEach { (itemId, quantity) ->
            val id = StockMovementEntity.orderMovementId(order.id, itemId)
            val old = existing[id]
            if (old == null || old.deletedAt != null || old.quantity != quantity) {
                movementDao.upsert(
                    StockMovementEntity(
                        id = id,
                        companyId = order.companyId,
                        itemId = itemId,
                        orderId = order.id,
                        kind = StockMovementKind.SERVICE_ORDER.serverId,
                        quantity = quantity,
                        unitCostCents = items[itemId]?.costCents ?: 0,
                        createdAt = old?.createdAt ?: time,
                        updatedAt = time,
                        serverUpdatedAt = old?.serverUpdatedAt
                    )
                )
                changed = true
            }
        }
        existing.values.filter { it.deletedAt == null && it.itemId !in wanted }.forEach { old ->
            movementDao.upsert(old.copy(deletedAt = time, updatedAt = time, isDirty = true))
            changed = true
        }
        if (changed) syncScheduler.requestSync()
    }
}

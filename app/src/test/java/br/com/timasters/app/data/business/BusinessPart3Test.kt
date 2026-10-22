package br.com.timasters.app.data.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class BusinessPart3Test {

    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(year: Int, month: Int, day: Int) =
        LocalDateTime.of(year, month, day, 10, 0).atZone(zone).toInstant().toEpochMilli()

    // region Stock

    @Test
    fun consumption_sumsRepeatedProducts_andIgnoresUntracked() {
        val items = listOf(
            OrderItem(catalogId = "cabo", description = "Cabo", quantity = 2.0, unitPriceCents = 100),
            OrderItem(catalogId = "cabo", description = "Cabo", quantity = 1.5, unitPriceCents = 100),
            OrderItem(catalogId = "conector", description = "RJ45", quantity = 10.0, unitPriceCents = 50),
            OrderItem(catalogId = null, description = "Visita", quantity = 1.0, unitPriceCents = 5000),
            OrderItem(catalogId = "servico", description = "Instalação", quantity = 1.0, unitPriceCents = 9000)
        )
        val result = StockLedger.orderConsumption(items, trackedIds = setOf("cabo", "conector"))
        assertEquals(mapOf("cabo" to -3.5, "conector" to -10.0), result)
    }

    @Test
    fun adjustment_isTheDifference_orNothing() {
        assertEquals(-2.0, StockLedger.adjustmentDelta(current = 10.0, counted = 8.0)!!, 0.0001)
        assertEquals(5.0, StockLedger.adjustmentDelta(current = -5.0, counted = 0.0)!!, 0.0001)
        assertNull(StockLedger.adjustmentDelta(current = 3.0, counted = 3.0))
    }

    @Test
    fun orderMovementId_isStablePerOrderAndProduct() {
        val a = StockMovementEntity.orderMovementId("os-1", "cabo")
        assertEquals(a, StockMovementEntity.orderMovementId("os-1", "cabo"))
        assertNotEquals(a, StockMovementEntity.orderMovementId("os-1", "conector"))
        assertNotEquals(a, StockMovementEntity.orderMovementId("os-2", "cabo"))
    }

    @Test
    fun lowStock_usesMinimum_orEmptyWhenNoMinimum() {
        val item = ServiceItemEntity(id = "x", companyId = "c", name = "Cabo", minStock = 5.0, createdAt = 0, updatedAt = 0)
        assertTrue(StockLevel(item, 5.0).isLow)
        assertFalse(StockLevel(item, 6.0).isLow)
        val noMinimum = item.copy(minStock = 0.0)
        assertTrue(StockLevel(noMinimum, 0.0).isLow)
        assertFalse(StockLevel(noMinimum, 1.0).isLow)
    }

    // endregion

    // region Dashboard

    private fun order(
        id: String,
        kind: OrderKind,
        status: OrderStatus,
        totalCents: Long,
        createdAt: Long,
        completedAt: Long? = null,
        clientId: String = "c1",
        clientName: String = "Ana",
        laborCents: Long = 0,
        items: List<OrderItem> = emptyList(),
        sentAt: Long? = null
    ) = ServiceOrderWithClient(
        order = ServiceOrderEntity(
            id = id, companyId = "co", clientId = clientId, kind = kind.serverId, status = status.serverId,
            itemsJson = OrderItem.encodeList(items), itemsCents = items.sumOf { it.totalCents }, laborCents = laborCents,
            totalCents = totalCents, sentAt = sentAt, completedAt = completedAt, createdAt = createdAt, updatedAt = createdAt
        ),
        clientName = clientName, clientPhone = null, ticketNumber = null, ticketTitle = null
    )

    @Test
    fun dashboard_countsConcludedOrdersInTheCompletionMonth() {
        val parts = listOf(OrderItem(catalogId = "cabo", description = "Cabo", quantity = 2.0, unitPriceCents = 5_000, unitCostCents = 2_000))
        val orders = listOf(
            // Created in September, concluded in October: counts in October.
            order("1", OrderKind.SERVICE_ORDER, OrderStatus.DONE, 30_000, at(2026, 9, 28), at(2026, 10, 2), laborCents = 20_000, items = parts),
            order("2", OrderKind.SERVICE_ORDER, OrderStatus.DONE, 10_000, at(2026, 10, 5), at(2026, 10, 6), clientId = "c2", clientName = "Bruno", laborCents = 10_000),
            order("3", OrderKind.SERVICE_ORDER, OrderStatus.DONE, 15_000, at(2026, 9, 1), at(2026, 9, 3)),
            order("4", OrderKind.SERVICE_ORDER, OrderStatus.IN_PROGRESS, 8_000, at(2026, 10, 10)),
            order("5", OrderKind.SERVICE_ORDER, OrderStatus.CANCELED, 99_000, at(2026, 10, 11)),
            order("6", OrderKind.QUOTE, OrderStatus.APPROVED, 5_000, at(2026, 10, 1)),
            order("7", OrderKind.QUOTE, OrderStatus.SENT, 5_000, at(2026, 10, 2)),
            order("8", OrderKind.QUOTE, OrderStatus.DRAFT, 5_000, at(2026, 10, 3)),
            order("9", OrderKind.QUOTE, OrderStatus.REJECTED, 5_000, at(2026, 10, 4))
        )
        val entries = listOf(
            TimeEntryEntity(id = "t1", companyId = "co", clientId = "c1", startedAt = at(2026, 10, 2), endedAt = at(2026, 10, 2) + 90 * 60_000, createdAt = 0, updatedAt = 0),
            TimeEntryEntity(id = "t2", companyId = "co", clientId = "c1", startedAt = at(2026, 9, 2), endedAt = at(2026, 9, 2) + 60 * 60_000, createdAt = 0, updatedAt = 0)
        )

        val s = BusinessDashboard.summarize(orders, entries, YearMonth.of(2026, 10), zone)

        assertEquals(40_000, s.revenueCents)
        assertEquals(15_000, s.previousRevenueCents)
        assertEquals(2, s.doneCount)
        assertEquals(20_000, s.averageTicketCents)
        assertEquals(30_000, s.laborCents)
        assertEquals(10_000, s.itemsCents)
        assertEquals(0, s.otherCents)
        assertEquals(4_000, s.partsCostCents)
        assertEquals(36_000, s.grossProfitCents)
        assertEquals(1, s.openOrdersCount)
        assertEquals(8_000, s.openOrdersCents)
        assertEquals(3, s.quotesSent)
        assertEquals(1, s.quotesApproved)
        assertEquals(33, s.approvalPercent)
        assertEquals(167, s.growthPercent)
        assertEquals(90, s.minutesWorked)
        assertEquals(listOf("Ana", "Bruno"), s.topClients.map { it.name })
        assertEquals(6, s.history.size)
        assertEquals(YearMonth.of(2026, 10), s.history.last().month)
        assertEquals(15_000, s.history[4].revenueCents)
    }

    @Test
    fun dashboard_emptyMonth_hasNoDivisionByZero() {
        val s = BusinessDashboard.summarize(emptyList(), emptyList(), YearMonth.of(2026, 1), zone)
        assertEquals(0, s.revenueCents)
        assertEquals(0, s.averageTicketCents)
        assertNull(s.approvalPercent)
        assertNull(s.growthPercent)
    }

    // endregion
}

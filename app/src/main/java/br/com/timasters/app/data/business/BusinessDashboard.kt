package br.com.timasters.app.data.business

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class MonthRevenue(val month: YearMonth, val revenueCents: Long)

data class ClientRevenue(val clientId: String, val name: String, val revenueCents: Long, val orders: Int)

/** Numbers of one month for the Business dashboard. Money in cents. */
data class DashboardSummary(
    val month: YearMonth,
    /** Total of the service orders concluded in the month. */
    val revenueCents: Long,
    val previousRevenueCents: Long,
    val doneCount: Int,
    val averageTicketCents: Long,
    val laborCents: Long,
    val itemsCents: Long,
    /** Urgency, travel fee and minimum charge, minus discounts. */
    val otherCents: Long,
    /** What the parts cost the company (known only for items with a catalog cost). */
    val partsCostCents: Long,
    val grossProfitCents: Long,
    /** Service orders still open or in progress, whatever their month. */
    val openOrdersCount: Int,
    val openOrdersCents: Long,
    val quotesSent: Int,
    val quotesApproved: Int,
    val minutesWorked: Long,
    val topClients: List<ClientRevenue>,
    /** The last [BusinessDashboard.HISTORY_MONTHS] months, oldest first, ending in [month]. */
    val history: List<MonthRevenue>
) {
    /** Share of the quotes sent in the month that were approved; null without quotes. */
    val approvalPercent: Int? get() = if (quotesSent == 0) null else Math.round(quotesApproved * 100f / quotesSent)

    /** Change against the previous month; null when the previous month had no revenue. */
    val growthPercent: Int?
        get() = if (previousRevenueCents <= 0) null
        else Math.round((revenueCents - previousRevenueCents) * 100f / previousRevenueCents)
}

object BusinessDashboard {
    const val HISTORY_MONTHS = 6
    private const val TOP_CLIENTS = 5

    fun summarize(
        orders: List<ServiceOrderWithClient>,
        timeEntries: List<TimeEntryEntity>,
        month: YearMonth,
        zone: ZoneId,
        now: Long = System.currentTimeMillis()
    ): DashboardSummary {
        fun monthOf(millis: Long) = YearMonth.from(Instant.ofEpochMilli(millis).atZone(zone))

        val serviceOrders = orders.filter { it.order.orderKind == OrderKind.SERVICE_ORDER && it.order.deletedAt == null }
        val done = serviceOrders.filter { OrderStatus.from(it.order.status) == OrderStatus.DONE }
        // Orders concluded before this field existed fall back to their last change.
        val doneByMonth = done.groupBy { monthOf(it.order.completedAt ?: it.order.updatedAt) }
        val inMonth = doneByMonth[month].orEmpty()

        val revenue = inMonth.sumOf { it.order.totalCents }
        val labor = inMonth.sumOf { it.order.laborCents }
        val items = inMonth.sumOf { it.order.itemsCents }
        val partsCost = inMonth.sumOf { entry -> entry.order.items.sumOf { it.totalCostCents } }

        val open = serviceOrders.filter { OrderStatus.from(it.order.status).isOpen }

        val quotes = orders.filter {
            it.order.orderKind == OrderKind.QUOTE && it.order.deletedAt == null && monthOf(it.order.createdAt) == month
        }
        val sentStatuses = setOf(OrderStatus.SENT, OrderStatus.APPROVED, OrderStatus.REJECTED)
        val sent = quotes.filter { OrderStatus.from(it.order.status) in sentStatuses || it.order.sentAt != null }
        val approved = quotes.count { OrderStatus.from(it.order.status) == OrderStatus.APPROVED }

        val minutes = timeEntries
            .filter { it.deletedAt == null && monthOf(it.startedAt) == month }
            .sumOf { it.durationMillis(now) } / 60_000

        val topClients = inMonth.groupBy { it.order.clientId }
            .map { (clientId, list) -> ClientRevenue(clientId, list.first().clientName, list.sumOf { it.order.totalCents }, list.size) }
            .sortedByDescending { it.revenueCents }
            .take(TOP_CLIENTS)

        val history = (HISTORY_MONTHS - 1 downTo 0).map { back ->
            val m = month.minusMonths(back.toLong())
            MonthRevenue(m, doneByMonth[m].orEmpty().sumOf { it.order.totalCents })
        }

        return DashboardSummary(
            month = month,
            revenueCents = revenue,
            previousRevenueCents = doneByMonth[month.minusMonths(1)].orEmpty().sumOf { it.order.totalCents },
            doneCount = inMonth.size,
            averageTicketCents = if (inMonth.isEmpty()) 0 else revenue / inMonth.size,
            laborCents = labor,
            itemsCents = items,
            otherCents = revenue - labor - items,
            partsCostCents = partsCost,
            grossProfitCents = revenue - partsCost,
            openOrdersCount = open.size,
            openOrdersCents = open.sumOf { it.order.totalCents },
            quotesSent = sent.size,
            quotesApproved = approved,
            minutesWorked = minutes,
            topClients = topClients,
            history = history
        )
    }
}

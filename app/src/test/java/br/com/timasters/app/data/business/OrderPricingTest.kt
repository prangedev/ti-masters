package br.com.timasters.app.data.business

import br.com.timasters.app.core.format.MoneyInput
import br.com.timasters.app.data.business.sync.ServiceOrderRow
import br.com.timasters.app.data.business.sync.toEntity
import br.com.timasters.app.data.business.sync.toPush
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrderPricingTest {

    private fun input(
        items: List<OrderItem> = emptyList(),
        minutes: Int = 0,
        rate: Long = 0,
        minimum: Long = 0,
        urgency: Int = 0,
        travel: Long = 0,
        discount: Long = 0
    ) = PricingInput(items, minutes, rate, minimum, urgency, travel, discount)

    @Test
    fun laborIsHoursTimesRate() {
        // 1h30 at R$ 100,00/h = R$ 150,00
        val result = OrderCalculator.calculate(input(minutes = 90, rate = 10_000))
        assertEquals(15_000, result.laborCents)
        assertEquals(15_000, result.totalCents)
    }

    @Test
    fun minimumChargeAppliesOnlyWhenThereIsLabor() {
        val short = OrderCalculator.calculate(input(minutes = 15, rate = 10_000, minimum = 8_000))
        assertEquals(8_000, short.laborCents)
        val partsOnly = OrderCalculator.calculate(
            input(items = listOf(OrderItem(description = "Cabo", unitPriceCents = 2_500)), minimum = 8_000)
        )
        assertEquals(0, partsOnly.laborCents)
        assertEquals(2_500, partsOnly.totalCents)
    }

    @Test
    fun urgencyIsOnLaborOnlyAndTravelAndDiscountAddUp() {
        val result = OrderCalculator.calculate(
            input(
                items = listOf(OrderItem(description = "Roteador", quantity = 2.0, unitPriceCents = 20_000)),
                minutes = 60, rate = 12_000, urgency = 50, travel = 3_000, discount = 5_000
            )
        )
        assertEquals(40_000, result.itemsCents)
        assertEquals(12_000, result.laborCents)
        assertEquals(6_000, result.urgencyCents)
        assertEquals(40_000 + 12_000 + 6_000 + 3_000 - 5_000L, result.totalCents)
    }

    @Test
    fun discountNeverMakesTotalNegative() {
        val result = OrderCalculator.calculate(input(minutes = 60, rate = 5_000, discount = 99_999))
        assertEquals(5_000, result.discountCents)
        assertEquals(0, result.totalCents)
    }

    @Test
    fun fractionalQuantitiesRoundToTheCent() {
        assertEquals(1_667, OrderItem(description = "Cabo", unit = "m", quantity = 3.333, unitPriceCents = 500).totalCents)
    }

    @Test
    fun parsesMoneyTypedTheBrazilianWay() {
        assertEquals(123_456L, MoneyInput.parseCents("1.234,56"))
        assertEquals(123_456L, MoneyInput.parseCents("R$ 1.234,56"))
        assertEquals(12_345L, MoneyInput.parseCents("123.45"))
        assertEquals(150_000L, MoneyInput.parseCents("1.500"))
        assertEquals(15_000L, MoneyInput.parseCents("150"))
        assertEquals(1_050L, MoneyInput.parseCents("10,5"))
        assertNull(MoneyInput.parseCents("abc"))
        assertNull(MoneyInput.parseCents("-5"))
        assertEquals("1234,50", MoneyInput.centsToInput(123_450))
        assertEquals(123_450L, MoneyInput.parseCents(MoneyInput.centsToInput(123_450)))
    }

    @Test
    fun parsesHours() {
        assertEquals(90, MoneyInput.parseHoursToMinutes("1:30"))
        assertEquals(90, MoneyInput.parseHoursToMinutes("1h30"))
        assertEquals(90, MoneyInput.parseHoursToMinutes("1,5"))
        assertEquals(120, MoneyInput.parseHoursToMinutes("2"))
        assertEquals(45, MoneyInput.parseHoursToMinutes("0:45"))
        assertEquals(0, MoneyInput.parseHoursToMinutes(""))
        assertNull(MoneyInput.parseHoursToMinutes("1:75"))
        assertNull(MoneyInput.parseHoursToMinutes("x"))
        assertEquals("1:05", MoneyInput.minutesToInput(65))
    }

    @Test
    fun itemsSurviveTheTripToTheServerAndBack() {
        val items = listOf(
            OrderItem(catalogId = "cat-1", description = "Configuração de roteador", unit = "un", quantity = 1.0, unitPriceCents = 15_000),
            OrderItem(description = "Cabo de rede", unit = "m", quantity = 12.5, unitPriceCents = 350)
        )
        val entity = ServiceOrderEntity(
            id = "o1", companyId = "c1", clientId = "cl1", kind = OrderKind.QUOTE.serverId,
            status = OrderStatus.DRAFT.serverId, itemsJson = OrderItem.encodeList(items),
            totalCents = 19_375, createdAt = 0, updatedAt = 0
        )
        val push = entity.toPush()
        val first = push.items[0].jsonObject
        assertEquals("cat-1", first["catalog_id"]!!.jsonPrimitive.content)
        assertEquals("15000", first["unit_price_cents"]!!.jsonPrimitive.content)

        // What the server sends back (jsonb) becomes the same items again.
        val serverJson = Json.encodeToString(ServiceOrderPushEcho(push.items))
        val row = ServiceOrderRow(
            id = "o1", companyId = "c1", clientId = "cl1", kind = "orcamento", number = 7, status = "rascunho",
            items = Json.parseToJsonElement(serverJson).jsonObject["items"]!!.jsonArray,
            totalCents = 19_375, createdAt = "2026-10-19T10:00:00Z", updatedAt = "2026-10-19T10:00:01Z"
        )
        val back = row.toEntity()
        assertEquals(items, back.items)
        assertEquals(7, back.number)
        assertEquals(OrderKind.QUOTE, back.orderKind)
    }

    @Test
    fun brokenItemsJsonMeansNoItems() {
        assertEquals(emptyList<OrderItem>(), OrderItem.decodeList("not json"))
    }

    @Test
    fun brandColorParsing() {
        assertEquals(0xFF1565C0.toInt(), parseHexColor("#1565C0"))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("#GGGGGG"))
    }

    @Test
    fun eachKindHasItsOwnStatuses() {
        assertEquals(OrderStatus.DRAFT, OrderStatus.initial(OrderKind.QUOTE))
        assertEquals(OrderStatus.OPEN, OrderStatus.initial(OrderKind.SERVICE_ORDER))
        assertEquals(4, OrderStatus.of(OrderKind.QUOTE).size)
        assertEquals(4, OrderStatus.of(OrderKind.SERVICE_ORDER).size)
    }
}

@kotlinx.serialization.Serializable
private data class ServiceOrderPushEcho(val items: kotlinx.serialization.json.JsonArray)

class PdfFileNameTest {
    @Test
    fun namesAreSafeForEveryApp() {
        assertEquals("Orcamento-12-Acao-Redes-Ltda.pdf", BusinessFiles.pdfFileName("Orçamento", "12", "Ação Redes Ltda."))
        assertEquals("Ordem-de-servico-3.pdf", BusinessFiles.pdfFileName("Ordem de serviço", "3", ""))
        assertEquals("documento.pdf", BusinessFiles.pdfFileName("!!!"))
    }
}

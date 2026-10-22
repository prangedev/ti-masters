package br.com.timasters.app.data.business

/** Everything needed to price a quote or service order. */
data class PricingInput(
    val items: List<OrderItem>,
    val laborMinutes: Int,
    val hourlyRateCents: Long,
    /** Applied to the labor when there is any labor (e.g. a minimum visit charge). */
    val minimumChargeCents: Long,
    val urgencyPercent: Int,
    val travelFeeCents: Long,
    val discountCents: Long
)

data class PricingResult(
    val itemsCents: Long,
    val laborCents: Long,
    val urgencyCents: Long,
    val travelFeeCents: Long,
    val discountCents: Long,
    val totalCents: Long
)

/**
 * Labor cost rules:
 * - labor = hours × hourly rate, rounded to the cent, never below the minimum charge;
 * - the urgency surcharge is a percentage of the labor only (parts are not marked up);
 * - the discount can't make the total negative.
 */
object OrderCalculator {

    fun calculate(input: PricingInput): PricingResult {
        val itemsCents = input.items.sumOf { it.totalCents }
        val baseLabor = Math.round(input.laborMinutes * input.hourlyRateCents / 60.0)
        val laborCents = if (input.laborMinutes > 0) maxOf(baseLabor, input.minimumChargeCents) else 0
        val urgencyCents = Math.round(laborCents * input.urgencyPercent / 100.0)
        val subtotal = itemsCents + laborCents + urgencyCents + input.travelFeeCents
        val discountCents = input.discountCents.coerceIn(0, subtotal)
        return PricingResult(
            itemsCents = itemsCents,
            laborCents = laborCents,
            urgencyCents = urgencyCents,
            travelFeeCents = input.travelFeeCents,
            discountCents = discountCents,
            totalCents = subtotal - discountCents
        )
    }
}

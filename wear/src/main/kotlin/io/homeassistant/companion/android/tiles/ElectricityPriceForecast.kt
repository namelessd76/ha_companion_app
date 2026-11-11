package io.homeassistant.companion.android.tiles

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Electricity price forecast retrieved from the forecast entity [entityId], with its [prices] sorted by time.
 *
 * Prices only change at the start of a quarter of an hour (see [ElectricityPriceForecastSchedule.PRICE_SLOT]),
 * so the price of any instant is the price of the quarter it belongs to.
 */
data class ElectricityPriceForecast(
    val entityId: String,
    val prices: List<HourlyPrice>,
    val unit: String,
    val retrievedAt: Instant,
) {
    /**
     * @return the price of the quarter of an hour [at] belongs to, null when the forecast does not cover it.
     *   An entry ends where the next one starts; the last one is assumed to be as long as the one before it.
     */
    fun priceAt(at: Instant): Double? {
        val slot = ElectricityPriceForecastSchedule.slotStart(at)
        val index = prices.indexOfLast { !it.time.toInstant().isAfter(slot) }
        val end = endOf(index) ?: return null
        return prices[index].price.takeIf { slot.isBefore(end) }
    }

    /** @return the end of the entry at [index], null when there is no such entry or its length is unknown. */
    private fun endOf(index: Int): Instant? {
        val entry = prices.getOrNull(index) ?: return null
        return prices.getOrNull(index + 1)?.time?.toInstant()
            ?: prices.getOrNull(index - 1)?.let { entry.time.toInstant().plus(Duration.between(it.time, entry.time)) }
    }

    /** @return the lowest and highest prices starting on [day] in [zone], null when there is none. */
    fun priceRangeOn(day: LocalDate, zone: ZoneId): ClosedFloatingPointRange<Double>? {
        val dayPrices = prices.filter { it.time.withZoneSameInstant(zone).toLocalDate() == day }.map { it.price }
        if (dayPrices.isEmpty()) return null
        return dayPrices.min()..dayPrices.max()
    }
}

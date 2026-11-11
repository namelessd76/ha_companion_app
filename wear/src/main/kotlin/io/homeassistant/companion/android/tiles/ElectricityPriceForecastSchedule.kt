package io.homeassistant.companion.android.tiles

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Timing and validity rules of the shared electricity price forecast (see [ElectricityPriceForecastRepository]).
 *
 * The forecast is retrieved once when the application starts and then every day at [DAILY_RETRIEVAL_TIME]
 * local time, when the next day's prices are published. A retrieval is retried every [RETRY_DELAY] until the
 * received forecast is valid, that is when it covers at least [REQUIRED_COVERAGE] from now.
 */
object ElectricityPriceForecastSchedule {

    val DAILY_RETRIEVAL_TIME: LocalTime = LocalTime.of(15, 0)
    val RETRY_DELAY: Duration = Duration.ofMinutes(1)
    val REQUIRED_COVERAGE: Duration = Duration.ofHours(12)

    /** Prices can only change at hh:00, hh:15, hh:30 and hh:45. */
    val PRICE_SLOT: Duration = Duration.ofMinutes(15)

    /** @return the start of the quarter-hour price slot [at] belongs to. */
    fun slotStart(at: Instant): Instant {
        val slotMillis = PRICE_SLOT.toMillis()
        return Instant.ofEpochMilli(Math.floorDiv(at.toEpochMilli(), slotMillis) * slotMillis)
    }

    /** @return the start of the next quarter-hour price slot, strictly after [at]. */
    fun nextSlotStart(at: Instant): Instant = slotStart(at).plus(PRICE_SLOT)

    /** @return the first [DAILY_RETRIEVAL_TIME] in [zone] strictly after [now]. */
    fun nextDailyRetrieval(now: Instant, zone: ZoneId): Instant {
        val today = now.atZone(zone).toLocalDate()
        val todayRetrieval = today.atTime(DAILY_RETRIEVAL_TIME).atZone(zone).toInstant()
        return if (todayRetrieval.isAfter(now)) {
            todayRetrieval
        } else {
            today.plusDays(1).atTime(DAILY_RETRIEVAL_TIME).atZone(zone).toInstant()
        }
    }

    /**
     * @return the end of the last entry of the time sorted [prices], null when it cannot be known. An entry ends
     *   where the next one starts, so the last one is assumed to be as long as the one before it.
     */
    fun coverageEnd(prices: List<HourlyPrice>): Instant? {
        if (prices.size < 2) return null
        val last = prices[prices.lastIndex].time.toInstant()
        val beforeLast = prices[prices.lastIndex - 1].time.toInstant()
        return last.plus(Duration.between(beforeLast, last))
    }

    /** @return whether [prices] cover at least [REQUIRED_COVERAGE] from [now]. */
    fun isValid(prices: List<HourlyPrice>, now: Instant): Boolean {
        val end = coverageEnd(prices) ?: return false
        return !end.isBefore(now.plus(REQUIRED_COVERAGE))
    }
}

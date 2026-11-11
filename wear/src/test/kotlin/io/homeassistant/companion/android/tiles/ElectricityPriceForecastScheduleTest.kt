package io.homeassistant.companion.android.tiles

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElectricityPriceForecastScheduleTest {

    private val zone = ZoneId.of("Europe/Helsinki")

    private fun quarters(from: Instant, count: Int): List<HourlyPrice> = (0 until count).map {
        HourlyPrice(from.plus(Duration.ofMinutes(15L * it)).atZone(zone), it.toDouble())
    }

    @Test
    fun `Given now before 15 local When computing next daily retrieval Then it is today at 15`() {
        val now = Instant.parse("2026-11-10T10:00:00Z") // 12:00 in Helsinki

        val next = ElectricityPriceForecastSchedule.nextDailyRetrieval(now, zone)

        assertEquals(Instant.parse("2026-11-10T13:00:00Z"), next)
    }

    @Test
    fun `Given now exactly at 15 local When computing next daily retrieval Then it is tomorrow at 15`() {
        val now = Instant.parse("2026-11-10T13:00:00Z")

        val next = ElectricityPriceForecastSchedule.nextDailyRetrieval(now, zone)

        assertEquals(Instant.parse("2026-11-11T13:00:00Z"), next)
    }

    @Test
    fun `Given a daylight saving change overnight When computing next daily retrieval Then it stays at 15 local`() {
        val now = Instant.parse("2026-10-24T20:00:00Z") // the night before the switch to winter time

        val next = ElectricityPriceForecastSchedule.nextDailyRetrieval(now, zone)

        assertEquals(Instant.parse("2026-10-25T13:00:00Z"), next)
    }

    @Test
    fun `Given quarter-hour prices When computing coverage end Then it is the end of the last quarter`() {
        val start = Instant.parse("2026-11-10T10:00:00Z")

        val end = ElectricityPriceForecastSchedule.coverageEnd(quarters(start, 8))

        assertEquals(Instant.parse("2026-11-10T12:00:00Z"), end)
    }

    @Test
    fun `Given a single price When computing coverage end Then it is unknown`() {
        assertNull(ElectricityPriceForecastSchedule.coverageEnd(quarters(Instant.parse("2026-11-10T10:00:00Z"), 1)))
    }

    @Test
    fun `Given prices covering exactly the next 12 hours When validating Then the forecast is valid`() {
        val now = Instant.parse("2026-11-10T10:00:00Z")

        assertTrue(ElectricityPriceForecastSchedule.isValid(quarters(now, 12 * 4), now))
    }

    @Test
    fun `Given prices missing the last quarter of the next 12 hours When validating Then the forecast is invalid`() {
        val now = Instant.parse("2026-11-10T10:00:00Z")

        assertFalse(ElectricityPriceForecastSchedule.isValid(quarters(now, 12 * 4 - 1), now))
    }

    @Test
    fun `Given no price When validating Then the forecast is invalid`() {
        assertFalse(ElectricityPriceForecastSchedule.isValid(emptyList(), Instant.parse("2026-11-10T10:00:00Z")))
    }
}

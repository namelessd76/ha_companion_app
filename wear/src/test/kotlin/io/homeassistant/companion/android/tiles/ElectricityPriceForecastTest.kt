package io.homeassistant.companion.android.tiles

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ElectricityPriceForecastTest {

    private val zone = ZoneId.of("Europe/Helsinki")

    /** Quarter-hour prices from [from], valued 0, 1, 2... */
    private fun forecast(from: Instant, count: Int) = ElectricityPriceForecast(
        entityId = "sensor.price",
        prices = (0 until count).map {
            HourlyPrice(from.plus(Duration.ofMinutes(15L * it)).atZone(zone), it.toDouble())
        },
        unit = "c/kWh",
        retrievedAt = from,
    )

    @Test
    fun `Given an instant inside a quarter When getting its price Then it is the price of that quarter`() {
        val forecast = forecast(Instant.parse("2026-11-10T10:00:00Z"), 8)

        assertEquals(2.0, forecast.priceAt(Instant.parse("2026-11-10T10:44:59.999Z")))
    }

    @Test
    fun `Given the exact start of a quarter When getting its price Then the new price applies right away`() {
        val forecast = forecast(Instant.parse("2026-11-10T10:00:00Z"), 8)

        assertEquals(3.0, forecast.priceAt(Instant.parse("2026-11-10T10:45:00Z")))
    }

    @Test
    fun `Given an instant after the last quarter When getting its price Then it is unknown`() {
        val forecast = forecast(Instant.parse("2026-11-10T10:00:00Z"), 8)

        assertNull(forecast.priceAt(Instant.parse("2026-11-10T12:00:00Z")))
    }

    @Test
    fun `Given an instant before the forecast When getting its price Then it is unknown`() {
        val forecast = forecast(Instant.parse("2026-11-10T10:00:00Z"), 8)

        assertNull(forecast.priceAt(Instant.parse("2026-11-10T09:59:59Z")))
    }

    @Test
    fun `Given a forecast over two days When getting today's range Then only today's prices count`() {
        // 21:00 to 03:00 local time, 24 quarters: 12 today (values 0..11), 12 tomorrow (values 12..23)
        val forecast = forecast(Instant.parse("2026-11-10T19:00:00Z"), 24)

        val range = forecast.priceRangeOn(LocalDate.of(2026, 11, 10), zone)

        assertEquals(0.0..11.0, range)
    }

    @Test
    fun `Given no price on a day When getting its range Then it is unknown`() {
        val forecast = forecast(Instant.parse("2026-11-10T10:00:00Z"), 8)

        assertNull(forecast.priceRangeOn(LocalDate.of(2026, 11, 12), zone))
    }
}

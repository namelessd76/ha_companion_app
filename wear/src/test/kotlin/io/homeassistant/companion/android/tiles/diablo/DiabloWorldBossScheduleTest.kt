package io.homeassistant.companion.android.tiles.diablo

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiabloWorldBossScheduleTest {

    private val reference = Instant.parse("2026-11-08T10:00:00Z")

    @Test
    fun `Given now right after reference When computing next spawn Then it is one interval later`() {
        val next = DiabloWorldBossSchedule.nextSpawn(reference, reference.plusSeconds(1))

        assertEquals(Instant.parse("2026-11-08T13:30:00Z"), next)
    }

    @Test
    fun `Given now exactly on a spawn When computing next spawn Then it is the following spawn`() {
        val next = DiabloWorldBossSchedule.nextSpawn(reference, Instant.parse("2026-11-08T13:30:00Z"))

        assertEquals(Instant.parse("2026-11-08T17:00:00Z"), next)
    }

    @Test
    fun `Given reference many days ago When computing next spawn Then it stays on the cycle`() {
        val now = Instant.parse("2026-11-20T00:00:00Z")

        val next = DiabloWorldBossSchedule.nextSpawn(reference, now)

        assertTrue(next.isAfter(now))
        assertTrue(Duration.between(now, next) <= DiabloWorldBossSchedule.SPAWN_INTERVAL)
        assertEquals(0L, Duration.between(reference, next).toMillis() % DiabloWorldBossSchedule.SPAWN_INTERVAL.toMillis())
    }

    @Test
    fun `Given reference in the future When computing next spawn Then earlier cycles are found`() {
        val next = DiabloWorldBossSchedule.nextSpawn(reference, Instant.parse("2026-11-08T04:00:00Z"))

        assertEquals(Instant.parse("2026-11-08T06:30:00Z"), next)
    }

    @Test
    fun `Given now 5 minutes before a spawn When computing next notified spawn Then the following spawn is used`() {
        val now = Instant.parse("2026-11-08T13:25:00Z")

        assertEquals(Instant.parse("2026-11-08T17:00:00Z"), DiabloWorldBossSchedule.nextNotifiedSpawn(reference, now))
    }

    @Test
    fun `Given now 15 minutes before a spawn When computing next notified spawn Then that spawn is used`() {
        val now = Instant.parse("2026-11-08T13:15:00Z")

        assertEquals(Instant.parse("2026-11-08T13:30:00Z"), DiabloWorldBossSchedule.nextNotifiedSpawn(reference, now))
    }

    @Test
    fun `Given a quiet period across midnight When checking times Then only night times match`() {
        val start = LocalTime.of(23, 0)
        val end = LocalTime.of(7, 0)

        assertTrue(DiabloWorldBossSchedule.isInQuietHours(LocalTime.of(23, 30), start, end))
        assertTrue(DiabloWorldBossSchedule.isInQuietHours(LocalTime.of(3, 0), start, end))
        assertFalse(DiabloWorldBossSchedule.isInQuietHours(LocalTime.of(7, 0), start, end))
        assertFalse(DiabloWorldBossSchedule.isInQuietHours(LocalTime.of(12, 0), start, end))
    }

    @Test
    fun `Given a quiet period from midnight to 8 When checking times Then the morning matches`() {
        val start = LocalTime.MIDNIGHT
        val end = LocalTime.of(8, 0)

        assertTrue(DiabloWorldBossSchedule.isInQuietHours(LocalTime.MIDNIGHT, start, end))
        assertTrue(DiabloWorldBossSchedule.isInQuietHours(LocalTime.of(7, 59), start, end))
        assertFalse(DiabloWorldBossSchedule.isInQuietHours(LocalTime.of(8, 0), start, end))
        assertFalse(DiabloWorldBossSchedule.isInQuietHours(LocalTime.of(23, 59), start, end))
    }

    @Test
    fun `Given an empty quiet period When checking a time Then it never matches`() {
        val time = LocalTime.of(5, 0)

        assertFalse(DiabloWorldBossSchedule.isInQuietHours(time, time, time))
    }

    @Test
    fun `Given a reference set in one zone When the watch moves to another zone Then the countdown is unchanged`() {
        val helsinki = ZoneId.of("Europe/Helsinki")
        // User enters 12:00 local time in Helsinki (10:00 UTC).
        val stored = DiabloWorldBossSchedule.withLocalTime(null, LocalTime.of(12, 0), helsinki, reference)
        val now = Instant.parse("2026-11-08T11:00:00Z")

        // The stored value is an absolute instant, so another current zone gives the same result.
        assertEquals(reference, stored)
        assertEquals(Duration.ofMinutes(150), DiabloWorldBossSchedule.timeUntilNextSpawn(stored, now))
    }

    @Test
    fun `Given an existing reference When changing its local date Then the local time is kept`() {
        val zone = ZoneId.of("America/New_York")

        val updated = DiabloWorldBossSchedule.withLocalDate(reference, LocalDate.of(2026, 11, 6), zone, reference)

        assertEquals(LocalTime.of(5, 0), updated.atZone(zone).toLocalTime())
        assertEquals(LocalDate.of(2026, 11, 6), updated.atZone(zone).toLocalDate())
    }

    @Test
    fun `Given remaining durations When formatting Then partial minutes are rounded up`() {
        assertEquals("3:30", DiabloWorldBossTile.formatRemaining(Duration.ofMinutes(210)))
        assertEquals("0:01", DiabloWorldBossTile.formatRemaining(Duration.ofSeconds(1)))
        assertEquals("1:05", DiabloWorldBossTile.formatRemaining(Duration.ofMinutes(64).plusSeconds(30)))
    }
}

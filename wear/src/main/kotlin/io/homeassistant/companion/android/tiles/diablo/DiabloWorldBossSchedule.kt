package io.homeassistant.companion.android.tiles.diablo

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure time math for the Diablo IV world boss cycle.
 *
 * Every computation works on absolute [Instant]s, so the results don't depend on the device time zone:
 * a reference spawn entered in one time zone keeps producing correct countdowns after the watch moves to
 * another. Only [isInQuietHours] works on local wall-clock time, on purpose: quiet hours follow the user's
 * current local night.
 */
object DiabloWorldBossSchedule {

    /** Time between two consecutive world boss spawns. */
    val SPAWN_INTERVAL: Duration = Duration.ofMinutes(210)

    /** How long before a spawn the reminder notification is sent. */
    val NOTIFICATION_LEAD: Duration = Duration.ofMinutes(10)

    /** @return the first spawn strictly after [now], given any known spawn [reference] (past or future). */
    fun nextSpawn(reference: Instant, now: Instant): Instant {
        val intervalMillis = SPAWN_INTERVAL.toMillis()
        val elapsedMillis = Duration.between(reference, now).toMillis()
        val cyclesPassed = Math.floorDiv(elapsedMillis, intervalMillis) + 1
        return reference.plusMillis(cyclesPassed * intervalMillis)
    }

    /** @return the time left from [now] until the next spawn; always in `(0, SPAWN_INTERVAL]`. */
    fun timeUntilNextSpawn(reference: Instant, now: Instant): Duration =
        Duration.between(now, nextSpawn(reference, now))

    /**
     * @return the spawn the next reminder belongs to: the first spawn whose reminder time
     *   (spawn - [NOTIFICATION_LEAD]) is strictly after [now].
     */
    fun nextNotifiedSpawn(reference: Instant, now: Instant): Instant = nextSpawn(reference, now.plus(NOTIFICATION_LEAD))

    /**
     * @return [reference] (or [now] when not set) moved to the local [date] in [zone], keeping its local
     *   time of day. The result is an absolute instant, so it stays valid after a time zone change.
     */
    fun withLocalDate(reference: Instant?, date: LocalDate, zone: ZoneId, now: Instant): Instant {
        val current = (reference ?: now).atZone(zone)
        return ZonedDateTime.of(date, current.toLocalTime(), zone).toInstant()
    }

    /**
     * @return [reference] (or [now] when not set) moved to the local [time] (to the minute) in [zone],
     *   keeping its local date. The result is an absolute instant, so it stays valid after a time zone change.
     */
    fun withLocalTime(reference: Instant?, time: LocalTime, zone: ZoneId, now: Instant): Instant {
        val current = (reference ?: now).atZone(zone)
        return ZonedDateTime.of(current.toLocalDate(), time.truncatedTo(ChronoUnit.MINUTES), zone).toInstant()
    }

    /**
     * @return whether [time] falls in the quiet period `[start, end)`. A period whose [end] is before
     *   [start] spans midnight (e.g. 23:00-07:00). An empty period (`start == end`) never matches.
     */
    fun isInQuietHours(time: LocalTime, start: LocalTime, end: LocalTime): Boolean = when {
        start == end -> false
        start < end -> time >= start && time < end
        else -> time >= start || time < end
    }
}

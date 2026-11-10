package io.homeassistant.companion.android.complications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import io.homeassistant.companion.android.tiles.HourlyPrice
import java.time.Duration
import java.time.Instant
import timber.log.Timber

/**
 * Timing of the electricity price (ranged value) complication.
 *
 * The price can only change every quarter of an hour, so the complication is refreshed once per quarter,
 * [REFRESH_OFFSET] after the quarter starts (at hh:00:01, hh:15:01, hh:30:01 and hh:45:01), and the value
 * stays valid for [VALIDITY] after that refresh instant, which leaves time for the next request to complete.
 *
 * The refresh is driven by a single alarm ([AlarmManager.RTC_WAKEUP], exact when allowed) that re-schedules
 * itself every time the complication is computed, so the chain stops by itself once no ranged value
 * complication is active anymore.
 */
object ElectricityPriceComplicationSchedule {

    internal const val ACTION_REFRESH = "io.homeassistant.companion.android.ELECTRICITY_PRICE_COMPLICATION_REFRESH"
    private const val REQUEST_CODE = 0xE1EC

    val SLOT: Duration = Duration.ofMinutes(15)
    val REFRESH_OFFSET: Duration = Duration.ofSeconds(1)
    val VALIDITY: Duration = Duration.ofMinutes(15).plusSeconds(15)

    /** Delay before retrying a failed refresh, as long as the previous value is still valid. */
    val RETRY_DELAY: Duration = Duration.ofSeconds(5)

    /** Delay before retrying a failed refresh when there is no valid value to show anymore. */
    val ERROR_RETRY_DELAY: Duration = Duration.ofMinutes(1)

    /**
     * Without a forecast, the entity state fetched right after the quarter starts may still be the previous
     * quarter's price (the server updates the sensor a bit later). When it did not change, it is fetched again
     * every [CONFIRM_DELAY] until [CONFIRM_WINDOW] after the quarter started.
     */
    val CONFIRM_DELAY: Duration = Duration.ofSeconds(30)
    val CONFIRM_WINDOW: Duration = Duration.ofMinutes(3)

    /** @return the start of the quarter-hour slot [now] belongs to. */
    fun slotStart(now: Instant): Instant {
        val slotMillis = SLOT.toMillis()
        return Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), slotMillis) * slotMillis)
    }

    /** @return the instant until which a value fetched for the slot starting at [slotStart] stays valid. */
    fun validUntil(slotStart: Instant): Instant = slotStart.plus(REFRESH_OFFSET).plus(VALIDITY)

    /** @return the next scheduled refresh instant strictly after [now]. */
    fun nextRefresh(now: Instant): Instant {
        val current = slotStart(now).plus(REFRESH_OFFSET)
        return if (current.isAfter(now)) current else current.plus(SLOT)
    }

    /** Schedules the next refresh at the next quarter-hour boundary (plus [REFRESH_OFFSET]). */
    fun scheduleNext(context: Context, now: Instant) {
        schedule(context, nextRefresh(now))
    }

    /**
     * Schedules a retry after a failed refresh. When the shown value ([shownValidUntil], null when an error is
     * shown) stays valid until the regular refresh, that refresh is enough. Otherwise the retry happens after
     * [RETRY_DELAY] (or [ERROR_RETRY_DELAY] when nothing valid is shown), unless the regular refresh is earlier.
     */
    fun scheduleRetry(context: Context, now: Instant, shownValidUntil: Instant?) {
        val next = nextRefresh(now)
        val retry = when {
            shownValidUntil == null || !shownValidUntil.isAfter(now) -> now.plus(ERROR_RETRY_DELAY)
            shownValidUntil.isBefore(next) -> now.plus(RETRY_DELAY)
            else -> next
        }
        schedule(context, minOf(retry, next))
    }

    /** Schedules a new fetch to confirm a price that may not be updated yet (see [CONFIRM_DELAY]). */
    fun scheduleConfirm(context: Context, now: Instant) {
        schedule(context, minOf(now.plus(CONFIRM_DELAY), nextRefresh(now)))
    }

    /** @return whether a price fetched at [now] is past the [CONFIRM_WINDOW] of its quarter. */
    fun isPastConfirmWindow(now: Instant): Boolean = !now.isBefore(slotStart(now).plus(CONFIRM_WINDOW))

    /**
     * @return the price of the [forecast] entry covering [at], or null when the forecast does not cover it.
     *   An entry ends where the next one starts; the last one is assumed to be as long as the one before it.
     */
    fun priceAt(forecast: List<HourlyPrice>, at: Instant): Double? {
        val index = forecast.indexOfLast { !it.time.toInstant().isAfter(at) }
        if (index == -1) return null
        val start = forecast[index].time.toInstant()
        val end = forecast.getOrNull(index + 1)?.time?.toInstant()
            ?: forecast.getOrNull(index - 1)?.let { start.plus(Duration.between(it.time, forecast[index].time)) }
            ?: return null
        return forecast[index].price.takeIf { at.isBefore(end) }
    }

    private fun schedule(context: Context, at: Instant) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val operation = pendingIntent(context)
        val triggerAtMillis = at.toEpochMilli()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
        Timber.d("Electricity price complication refresh scheduled at $at")
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ComplicationReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

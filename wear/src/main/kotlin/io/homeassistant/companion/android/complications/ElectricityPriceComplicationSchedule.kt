package io.homeassistant.companion.android.complications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import io.homeassistant.companion.android.common.util.SdkVersion
import io.homeassistant.companion.android.tiles.ElectricityPriceForecastSchedule
import java.time.Instant
import timber.log.Timber

/**
 * Timing of the electricity price complication ([ElectricityPriceDataSourceService]).
 *
 * The price can only change at the start of a quarter of an hour, and it is read from the forecast already on the
 * watch, so the complication is refreshed exactly when a quarter starts (hh:00, hh:15, hh:30 and hh:45). The
 * refresh is driven by a single alarm ([AlarmManager.RTC_WAKEUP], exact when allowed) that re-schedules itself
 * every time the complication is computed, so the chain stops by itself once no price complication is active.
 */
object ElectricityPriceComplicationSchedule {

    internal const val ACTION_REFRESH = "io.homeassistant.companion.android.ELECTRICITY_PRICE_COMPLICATION_REFRESH"
    private const val REQUEST_CODE = 0xE1EC

    /** Schedules the next refresh at the start of the next quarter of an hour after [now]. */
    fun scheduleNext(context: Context, now: Instant) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val at = ElectricityPriceForecastSchedule.nextSlotStart(now)
        val operation = pendingIntent(context)
        val triggerAtMillis = at.toEpochMilli()
        if (!SdkVersion.isAtLeast(Build.VERSION_CODES.S) || alarmManager.canScheduleExactAlarms()) {
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

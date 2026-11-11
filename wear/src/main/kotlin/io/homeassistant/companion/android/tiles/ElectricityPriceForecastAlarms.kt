package io.homeassistant.companion.android.tiles

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import io.homeassistant.companion.android.common.util.SdkVersion
import java.time.Instant
import java.time.ZoneId
import timber.log.Timber

/**
 * The two alarms driving [ElectricityPriceForecastRepository]: the daily retrieval and the retry of a retrieval
 * that did not give a valid forecast. They are separate, so a retry (or a manual refresh) never moves the daily
 * retrieval.
 *
 * Alarms are set on wall-clock instants ([AlarmManager.RTC_WAKEUP]), exact when the user granted it, otherwise
 * inexact (Doze-friendly, possibly a few minutes late).
 */
internal object ElectricityPriceForecastAlarms {

    const val ACTION_DAILY_RETRIEVAL = "io.homeassistant.companion.android.ELECTRICITY_PRICE_FORECAST_DAILY"
    const val ACTION_RETRY = "io.homeassistant.companion.android.ELECTRICITY_PRICE_FORECAST_RETRY"
    private const val REQUEST_CODE_DAILY = 0xE1EF
    private const val REQUEST_CODE_RETRY = 0xE1F0

    /** Schedules the next daily retrieval after [now] (see [ElectricityPriceForecastSchedule.nextDailyRetrieval]). */
    fun scheduleDailyRetrieval(context: Context, now: Instant) {
        val at = ElectricityPriceForecastSchedule.nextDailyRetrieval(now, ZoneId.systemDefault())
        schedule(context, at, ACTION_DAILY_RETRIEVAL, REQUEST_CODE_DAILY)
    }

    /** Schedules a retry [ElectricityPriceForecastSchedule.RETRY_DELAY] after [now], replacing any pending one. */
    fun scheduleRetry(context: Context, now: Instant) {
        schedule(context, now.plus(ElectricityPriceForecastSchedule.RETRY_DELAY), ACTION_RETRY, REQUEST_CODE_RETRY)
    }

    /** Cancels the pending retry, if any. */
    fun cancelRetry(context: Context) {
        context.getSystemService<AlarmManager>()?.cancel(pendingIntent(context, ACTION_RETRY, REQUEST_CODE_RETRY))
    }

    private fun schedule(context: Context, at: Instant, action: String, requestCode: Int) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val operation = pendingIntent(context, action, requestCode)
        val triggerAtMillis = at.toEpochMilli()
        if (!SdkVersion.isAtLeast(Build.VERSION_CODES.S) || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
        Timber.d("Electricity price forecast $action scheduled at $at")
    }

    private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ElectricityPriceForecastReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

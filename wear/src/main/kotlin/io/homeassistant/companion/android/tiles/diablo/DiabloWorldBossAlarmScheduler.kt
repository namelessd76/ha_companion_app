package io.homeassistant.companion.android.tiles.diablo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import io.homeassistant.companion.android.common.data.prefs.impl.entities.DiabloWorldBossTileConfig
import java.time.Instant
import timber.log.Timber

/**
 * Schedules (or cancels) the single pending alarm that triggers the next Diablo IV world boss reminder.
 *
 * Alarms are set on absolute wall-clock instants ([AlarmManager.RTC_WAKEUP]), so they keep firing at the
 * right moment when the watch changes time zone. Exact alarms are used when the user granted them,
 * otherwise an inexact (Doze-friendly, possibly a few minutes late) alarm is used.
 */
object DiabloWorldBossAlarmScheduler {

    internal const val ACTION_REMINDER = "io.homeassistant.companion.android.DIABLO_WORLD_BOSS_REMINDER"
    internal const val EXTRA_SPAWN_EPOCH_MILLIS = "spawn_epoch_millis"
    private const val REQUEST_CODE = 0xD1AB

    /**
     * Schedules the reminder for the next spawn after [now] when notifications are enabled and a reference
     * spawn is configured in [config], otherwise cancels any pending reminder.
     */
    fun update(context: Context, config: DiabloWorldBossTileConfig, now: Instant) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val reference = config.referenceSpawn
        if (!config.notificationsEnabled || reference == null) {
            alarmManager.cancel(pendingIntent(context, spawn = null))
            return
        }
        val spawn = DiabloWorldBossSchedule.nextNotifiedSpawn(reference, now)
        val triggerAtMillis = spawn.minus(DiabloWorldBossSchedule.NOTIFICATION_LEAD).toEpochMilli()
        val operation = pendingIntent(context, spawn)
        if (canScheduleExactAlarms(alarmManager)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
        Timber.d("Diablo world boss reminder scheduled for spawn at $spawn")
    }

    /** @return whether the app may schedule exact alarms (always true before Android 12). */
    fun canScheduleExactAlarms(context: Context): Boolean =
        context.getSystemService<AlarmManager>()?.let(::canScheduleExactAlarms) ?: false

    private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(context: Context, spawn: Instant?): PendingIntent {
        val intent = Intent(context, DiabloWorldBossReceiver::class.java)
            .setAction(ACTION_REMINDER)
            .apply { spawn?.let { putExtra(EXTRA_SPAWN_EPOCH_MILLIS, it.toEpochMilli()) } }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

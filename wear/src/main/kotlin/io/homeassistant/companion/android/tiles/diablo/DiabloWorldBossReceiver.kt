package io.homeassistant.companion.android.tiles.diablo

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.prefs.WearPrefsRepository
import io.homeassistant.companion.android.common.data.prefs.impl.entities.DiabloWorldBossTileConfig
import io.homeassistant.companion.android.common.util.CHANNEL_DIABLO_WORLD_BOSS
import io.homeassistant.companion.android.common.util.launchAsync
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Posts the Diablo IV world boss reminder when its alarm fires (unless the current local time is within
 * the configured quiet hours) and keeps the next reminder scheduled, including after a reboot, an app
 * update, a manual clock change or a change of the exact alarm permission.
 */
@AndroidEntryPoint
class DiabloWorldBossReceiver : BroadcastReceiver() {

    @Inject
    lateinit var wearPrefsRepository: WearPrefsRepository

    @Inject
    lateinit var clock: Clock

    // Same threading as the other receivers; there is no injectable dispatcher in this module yet.
    @Suppress("InjectDispatcher")
    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        launchAsync(receiverScope) {
            val config = wearPrefsRepository.getDiabloWorldBossTileConfig()
            var now = Instant.ofEpochMilli(clock.now().toEpochMilliseconds())
            if (intent.action == DiabloWorldBossAlarmScheduler.ACTION_REMINDER) {
                val spawn = intent.getLongExtra(DiabloWorldBossAlarmScheduler.EXTRA_SPAWN_EPOCH_MILLIS, -1L)
                    .takeIf { it > 0 }
                    ?.let(Instant::ofEpochMilli)
                if (spawn != null) {
                    if (shouldNotify(config, spawn, now)) showNotification(context, spawn)
                    // Never re-schedule the same spawn, even if the alarm fired a bit early.
                    now = maxOf(now, spawn.minus(DiabloWorldBossSchedule.NOTIFICATION_LEAD))
                }
            }
            DiabloWorldBossAlarmScheduler.update(context, config, now)
        }
    }

    private fun shouldNotify(config: DiabloWorldBossTileConfig, spawn: Instant, now: Instant): Boolean {
        val isActive = config.notificationsEnabled && config.referenceSpawn != null
        // Fired after the spawn (e.g. the device was off): there is nothing left to remind.
        val isLate = now.isAfter(spawn)
        // Quiet hours follow the zone the watch is in right now, not the one the reference was set in.
        val localNow = now.atZone(ZoneId.systemDefault()).toLocalTime()
        val isQuiet = config.quietHoursEnabled &&
            DiabloWorldBossSchedule.isInQuietHours(localNow, config.quietStart, config.quietEnd)
        return isActive && !isLate && !isQuiet
    }

    @SuppressLint("MissingPermission") // Guarded by areNotificationsEnabled()
    private fun showNotification(context: Context, spawn: Instant) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Timber.w("Notifications are disabled, skipping Diablo world boss reminder")
            return
        }
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_DIABLO_WORLD_BOSS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(commonR.string.diablo_world_boss_channel))
                .build(),
        )
        val spawnText = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(spawn)
        val notification = NotificationCompat.Builder(context, CHANNEL_DIABLO_WORLD_BOSS)
            .setSmallIcon(commonR.drawable.ic_stat_ic_notification)
            .setContentTitle(context.getString(commonR.string.diablo_world_boss_notification_title))
            .setContentText(context.getString(commonR.string.diablo_world_boss_notification_text, spawnText))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT.toMillis())
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_ID = 0xD1AB

        /** Dismiss the reminder a bit after the boss has spawned. */
        val NOTIFICATION_TIMEOUT: Duration = DiabloWorldBossSchedule.NOTIFICATION_LEAD.plusMinutes(5)

        val HANDLED_ACTIONS = setOf(
            DiabloWorldBossAlarmScheduler.ACTION_REMINDER,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}

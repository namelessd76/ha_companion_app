package io.homeassistant.companion.android.common.data.prefs.impl.entities

import java.time.Instant
import java.time.LocalTime
import kotlinx.serialization.Serializable

private const val DEFAULT_QUIET_START_MINUTE = 0 // 00:00
private const val DEFAULT_QUIET_END_MINUTE = 8 * 60 // 08:00
private const val SECONDS_PER_MINUTE = 60L

/**
 * Configuration for the single, global Diablo IV world boss tile.
 *
 * @param referenceSpawnEpochMillis a confirmed past world boss spawn, as an absolute instant (UTC epoch
 *   millis). Being time zone independent, the countdown stays correct when the watch changes time zone.
 *   `null` when not configured yet.
 * @param notificationsEnabled whether a notification is sent shortly before every spawn
 * @param quietHoursEnabled whether notifications are suppressed during the quiet period
 * @param quietStartMinuteOfDay start of the quiet period, in minutes after local midnight
 * @param quietEndMinuteOfDay end (exclusive) of the quiet period, in minutes after local midnight; may be
 *   before [quietStartMinuteOfDay], in which case the period spans midnight
 */
@Serializable
data class DiabloWorldBossTileConfig(
    val referenceSpawnEpochMillis: Long? = null,
    val notificationsEnabled: Boolean = false,
    val quietHoursEnabled: Boolean = true,
    val quietStartMinuteOfDay: Int = DEFAULT_QUIET_START_MINUTE,
    val quietEndMinuteOfDay: Int = DEFAULT_QUIET_END_MINUTE,
) {
    /** The reference spawn as an [Instant], or `null` when not configured. */
    val referenceSpawn: Instant?
        get() = referenceSpawnEpochMillis?.let(Instant::ofEpochMilli)

    /** Local start of the quiet period. */
    val quietStart: LocalTime
        get() = LocalTime.ofSecondOfDay(quietStartMinuteOfDay * SECONDS_PER_MINUTE)

    /** Local end (exclusive) of the quiet period. */
    val quietEnd: LocalTime
        get() = LocalTime.ofSecondOfDay(quietEndMinuteOfDay * SECONDS_PER_MINUTE)
}

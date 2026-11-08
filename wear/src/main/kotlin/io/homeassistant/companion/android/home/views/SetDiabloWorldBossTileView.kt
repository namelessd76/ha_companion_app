package io.homeassistant.companion.android.home.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import io.github.timoptr.mdiicons.Mdi
import io.github.timoptr.mdiicons.MdiIcon
import io.github.timoptr.mdiicons.generated.AlarmCheck
import io.github.timoptr.mdiicons.generated.BellRing
import io.github.timoptr.mdiicons.generated.BellSleep
import io.github.timoptr.mdiicons.generated.CalendarMonth
import io.github.timoptr.mdiicons.generated.ClockEnd
import io.github.timoptr.mdiicons.generated.ClockOutline
import io.github.timoptr.mdiicons.generated.ClockStart
import io.github.timoptr.mdiicons.generated.TimerSand
import io.github.timoptr.mdiicons.rememberImageVector
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.prefs.impl.entities.DiabloWorldBossTileConfig
import io.homeassistant.companion.android.theme.WearAppTheme
import io.homeassistant.companion.android.theme.getFilledTonalButtonColors
import io.homeassistant.companion.android.theme.getSwitchButtonColors
import io.homeassistant.companion.android.theme.wearColorScheme
import io.homeassistant.companion.android.views.ListHeader
import io.homeassistant.companion.android.views.ThemeLazyColumn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val SECTION_SPACING = 4.dp
private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/**
 * Settings of the Diablo IV world boss tile. Dates and times are shown in the watch's current time
 * zone, while the reference spawn itself is stored as an absolute instant.
 *
 * @param nextSpawn the next spawn computed from the current config, or `null` when no reference is set
 * @param showExactAlarmWarning whether to offer granting the exact alarm permission
 */
@Composable
fun SetDiabloWorldBossTileView(
    config: DiabloWorldBossTileConfig,
    nextSpawn: Instant?,
    showExactAlarmWarning: Boolean,
    onSelectReferenceDate: () -> Unit,
    onSelectReferenceTime: () -> Unit,
    onNotificationsEnabled: (Boolean) -> Unit,
    onQuietHoursEnabled: (Boolean) -> Unit,
    onSelectQuietStart: () -> Unit,
    onSelectQuietEnd: () -> Unit,
    onAllowExactAlarms: () -> Unit,
) {
    WearAppTheme {
        ThemeLazyColumn {
            item { ListHeader(commonR.string.diablo_world_boss_tile) }
            item { ReferenceSection(config, nextSpawn, onSelectReferenceDate, onSelectReferenceTime) }
            item {
                NotificationSection(
                    config = config,
                    showExactAlarmWarning = showExactAlarmWarning,
                    onNotificationsEnabled = onNotificationsEnabled,
                    onQuietHoursEnabled = onQuietHoursEnabled,
                    onSelectQuietStart = onSelectQuietStart,
                    onSelectQuietEnd = onSelectQuietEnd,
                    onAllowExactAlarms = onAllowExactAlarms,
                )
            }
        }
    }
}

/** The next spawn (read-only) and the controls to set the reference spawn. */
@Composable
private fun ReferenceSection(
    config: DiabloWorldBossTileConfig,
    nextSpawn: Instant?,
    onSelectReferenceDate: () -> Unit,
    onSelectReferenceTime: () -> Unit,
) {
    val none = stringResource(commonR.string.none)
    val reference = config.referenceSpawn?.atZone(ZoneId.systemDefault())
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
        SettingsButton(
            icon = Mdi.TimerSand,
            label = stringResource(commonR.string.diablo_world_boss_next_spawn),
            secondaryLabel = nextSpawn?.atZone(ZoneId.systemDefault())?.format(dateTimeFormatter) ?: none,
            enabled = false,
            onClick = {},
        )
        SettingsButton(
            icon = Mdi.CalendarMonth,
            label = stringResource(commonR.string.diablo_world_boss_reference_date),
            secondaryLabel = reference?.format(dateFormatter) ?: none,
            onClick = onSelectReferenceDate,
        )
        SettingsButton(
            icon = Mdi.ClockOutline,
            label = stringResource(commonR.string.diablo_world_boss_reference_time),
            secondaryLabel = reference?.format(timeFormatter) ?: none,
            onClick = onSelectReferenceTime,
        )
    }
}

/** The reminder toggle, the exact alarm permission hint and the quiet hours. */
@Composable
private fun NotificationSection(
    config: DiabloWorldBossTileConfig,
    showExactAlarmWarning: Boolean,
    onNotificationsEnabled: (Boolean) -> Unit,
    onQuietHoursEnabled: (Boolean) -> Unit,
    onSelectQuietStart: () -> Unit,
    onSelectQuietEnd: () -> Unit,
    onAllowExactAlarms: () -> Unit,
) {
    val quietTimesEnabled = config.notificationsEnabled && config.quietHoursEnabled
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
        SettingsSwitch(
            icon = Mdi.BellRing,
            label = stringResource(commonR.string.diablo_world_boss_notify),
            checked = config.notificationsEnabled,
            onCheckedChange = onNotificationsEnabled,
        )
        if (showExactAlarmWarning) {
            SettingsButton(
                icon = Mdi.AlarmCheck,
                label = stringResource(commonR.string.diablo_world_boss_allow_exact_alarms),
                secondaryLabel = null,
                onClick = onAllowExactAlarms,
            )
        }
        SettingsSwitch(
            icon = Mdi.BellSleep,
            label = stringResource(commonR.string.diablo_world_boss_quiet_hours),
            checked = config.quietHoursEnabled,
            enabled = config.notificationsEnabled,
            onCheckedChange = onQuietHoursEnabled,
        )
        SettingsButton(
            icon = Mdi.ClockStart,
            label = stringResource(commonR.string.diablo_world_boss_quiet_start),
            secondaryLabel = config.quietStart.format(timeFormatter),
            enabled = quietTimesEnabled,
            onClick = onSelectQuietStart,
        )
        SettingsButton(
            icon = Mdi.ClockEnd,
            label = stringResource(commonR.string.diablo_world_boss_quiet_end),
            secondaryLabel = config.quietEnd.format(timeFormatter),
            enabled = quietTimesEnabled,
            onClick = onSelectQuietEnd,
        )
    }
}

@Composable
private fun SettingsButton(
    icon: MdiIcon,
    label: String,
    secondaryLabel: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        icon = { SettingsIcon(icon) },
        colors = getFilledTonalButtonColors(),
        label = { Text(text = label) },
        secondaryLabel = secondaryLabel?.let { { Text(text = it) } },
        onClick = onClick,
    )
}

@Composable
private fun SettingsSwitch(
    icon: MdiIcon,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SwitchButton(
        modifier = Modifier.fillMaxWidth(),
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
        label = { Text(text = label) },
        icon = { SettingsIcon(icon) },
        colors = getSwitchButtonColors(),
    )
}

@Composable
private fun SettingsIcon(icon: MdiIcon) {
    Image(
        imageVector = icon.rememberImageVector(),
        contentDescription = null,
        colorFilter = ColorFilter.tint(wearColorScheme.onSurface),
    )
}

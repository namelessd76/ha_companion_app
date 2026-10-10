package io.homeassistant.companion.android.home.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import io.github.timoptr.mdiicons.Mdi
import io.github.timoptr.mdiicons.MdiIcon
import io.github.timoptr.mdiicons.generated.Cash
import io.github.timoptr.mdiicons.generated.Flash
import io.github.timoptr.mdiicons.generated.TimerCog
import io.github.timoptr.mdiicons.rememberImageVector
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.integration.display.EntityDisplay
import io.homeassistant.companion.android.common.data.prefs.impl.entities.ElectricityPriceTileConfig
import io.homeassistant.companion.android.theme.WearAppTheme
import io.homeassistant.companion.android.theme.getFilledTonalButtonColors
import io.homeassistant.companion.android.theme.wearColorScheme
import io.homeassistant.companion.android.util.intervalToString
import io.homeassistant.companion.android.views.ListHeader
import io.homeassistant.companion.android.views.ThemeLazyColumn

@Composable
fun SetElectricityPriceTileView(
    tile: ElectricityPriceTileConfig,
    entityItem: EntityDisplay?,
    currentPriceEntityItem: EntityDisplay?,
    onSelectEntity: () -> Unit,
    onSelectCurrentPriceEntity: () -> Unit,
    onSelectRefreshInterval: () -> Unit,
) {
    WearAppTheme {
        ThemeLazyColumn {
            item {
                ListHeader(commonR.string.electricity_price_tile)
            }
            item {
                SettingsButton(
                    icon = entityItem?.icon ?: Mdi.Flash,
                    label = stringResource(id = commonR.string.electricity_price_tile_forecast_entity),
                    secondaryLabel = entityItem?.name
                        ?: tile.entityId.ifBlank { stringResource(id = commonR.string.none) },
                    onClick = onSelectEntity,
                )
            }
            item {
                SettingsButton(
                    icon = currentPriceEntityItem?.icon ?: Mdi.Cash,
                    label = stringResource(id = commonR.string.electricity_price_tile_current_price_entity),
                    secondaryLabel = currentPriceEntityItem?.name
                        ?: tile.currentPriceEntityId.ifBlank { stringResource(id = commonR.string.none) },
                    onClick = onSelectCurrentPriceEntity,
                )
            }
            item {
                SettingsButton(
                    icon = Mdi.TimerCog,
                    label = stringResource(id = commonR.string.refresh_interval),
                    secondaryLabel = intervalToString(LocalContext.current, tile.refreshInterval),
                    onClick = onSelectRefreshInterval,
                )
            }
        }
    }
}

@Composable
private fun SettingsButton(icon: MdiIcon, label: String, secondaryLabel: String, onClick: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        icon = {
            Image(
                imageVector = icon.rememberImageVector(),
                contentDescription = null,
                colorFilter = ColorFilter.tint(wearColorScheme.onSurface),
            )
        },
        colors = getFilledTonalButtonColors(),
        label = { Text(text = label) },
        secondaryLabel = { Text(text = secondaryLabel) },
        onClick = onClick,
    )
}

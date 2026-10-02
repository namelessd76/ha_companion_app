package io.homeassistant.companion.android.home.views

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import com.mikepenz.iconics.compose.Image
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.friendlyName
import io.homeassistant.companion.android.common.data.integration.getIcon
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
    entities: List<Entity<*>>?,
    onSelectEntity: () -> Unit,
    onSelectCurrentPriceEntity: () -> Unit,
    onSelectRefreshInterval: () -> Unit
) {
    WearAppTheme {
        ThemeLazyColumn {
            item {
                ListHeader(commonR.string.electricity_price_tile)
            }
            item {
                val entity = tile.entityId.takeIf { it.isNotBlank() }?.let { tileEntityId ->
                    entities?.firstOrNull { it.entityId == tileEntityId }
                }
                val icon = entity?.getIcon(LocalContext.current) ?: CommunityMaterial.Icon2.cmd_flash
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Image(
                            asset = icon,
                            colorFilter = ColorFilter.tint(wearColorScheme.onSurface)
                        )
                    },
                    colors = getFilledTonalButtonColors(),
                    label = {
                        Text(
                            text = stringResource(id = commonR.string.electricity_price_tile_forecast_entity)
                        )
                    },
                    secondaryLabel = {
                        Text(entity?.friendlyName ?: tile.entityId.ifBlank { stringResource(id = R.string.none) })
                    },
                    onClick = onSelectEntity
                )
            }
            item {
                val entity = tile.currentPriceEntityId.takeIf { it.isNotBlank() }?.let { tileEntityId ->
                    entities?.firstOrNull { it.entityId == tileEntityId }
                }
                val icon = entity?.getIcon(LocalContext.current) ?: CommunityMaterial.Icon.cmd_cash
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Image(
                            asset = icon,
                            colorFilter = ColorFilter.tint(wearColorScheme.onSurface)
                        )
                    },
                    colors = getFilledTonalButtonColors(),
                    label = {
                        Text(
                            text = stringResource(id = commonR.string.electricity_price_tile_current_price_entity)
                        )
                    },
                    secondaryLabel = {
                        Text(entity?.friendlyName ?: tile.currentPriceEntityId.ifBlank { stringResource(id = R.string.none) })
                    },
                    onClick = onSelectCurrentPriceEntity
                )
            }
            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Image(
                            asset = CommunityMaterial.Icon3.cmd_timer_cog,
                            colorFilter = ColorFilter.tint(wearColorScheme.onSurface)
                        )
                    },
                    colors = getFilledTonalButtonColors(),
                    label = {
                        Text(
                            text = stringResource(id = commonR.string.refresh_interval)
                        )
                    },
                    secondaryLabel = {
                        Text(
                            intervalToString(LocalContext.current, tile.refreshInterval)
                        )
                    },
                    onClick = onSelectRefreshInterval
                )
            }
        }
    }
}

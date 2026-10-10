package io.homeassistant.companion.android.common.data.prefs.impl.entities

import kotlinx.serialization.Serializable

/**
 * Configuration for the single, global Electricity price tile.
 *
 * @param entityId the entity that exposes the hourly price forecast (e.g. as `raw_today`/`raw_tomorrow`
 *   attributes used by the Nord Pool integration, a generic `forecast` attribute, or Tibber-style
 *   `today`/`tomorrow` attributes)
 * @param currentPriceEntityId optional separate entity whose state is used as the 'current price'
 *   headline; when blank, the current price is derived from the forecast entity's own data instead
 * @param refreshInterval how often (in seconds) the tile should refresh; `<= 1` means always refresh
 */
@Serializable
data class ElectricityPriceTileConfig(
    val entityId: String = "",
    val currentPriceEntityId: String = "",
    val refreshInterval: Int = 0,
)

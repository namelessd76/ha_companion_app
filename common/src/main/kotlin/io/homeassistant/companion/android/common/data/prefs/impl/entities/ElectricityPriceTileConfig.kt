package io.homeassistant.companion.android.common.data.prefs.impl.entities

import kotlinx.serialization.Serializable

/**
 * Configuration for the single, global Electricity price tile.
 *
 * @param entityId the entity that exposes the hourly price forecast (e.g. as `raw_today`/`raw_tomorrow`
 *   attributes used by the Nord Pool integration, a generic `forecast` attribute, or Tibber-style
 *   `today`/`tomorrow` attributes)
 * @param refreshInterval how often (in seconds) the tile should refresh; `<= 1` means always refresh
 */
@Serializable
data class ElectricityPriceTileConfig(val entityId: String = "", val refreshInterval: Int = 0)

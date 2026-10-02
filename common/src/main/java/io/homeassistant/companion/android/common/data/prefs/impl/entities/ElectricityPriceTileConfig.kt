package io.homeassistant.companion.android.common.data.prefs.impl.entities

import com.fasterxml.jackson.annotation.JsonProperty
import org.json.JSONObject

const val FIELD_ELECTRICITY_PRICE_ENTITY_ID = "entity_id"
const val FIELD_ELECTRICITY_PRICE_CURRENT_PRICE_ENTITY_ID = "current_price_entity_id"
const val FIELD_ELECTRICITY_PRICE_REFRESH_INTERVAL = "refresh_interval"

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
data class ElectricityPriceTileConfig(
    @JsonProperty(value = FIELD_ELECTRICITY_PRICE_ENTITY_ID)
    val entityId: String = "",
    @JsonProperty(value = FIELD_ELECTRICITY_PRICE_CURRENT_PRICE_ENTITY_ID)
    val currentPriceEntityId: String = "",
    @JsonProperty(value = FIELD_ELECTRICITY_PRICE_REFRESH_INTERVAL)
    val refreshInterval: Int = 0
) {
    constructor(jsonObject: JSONObject) : this(
        jsonObject.optString(FIELD_ELECTRICITY_PRICE_ENTITY_ID, ""),
        jsonObject.optString(FIELD_ELECTRICITY_PRICE_CURRENT_PRICE_ENTITY_ID, ""),
        jsonObject.optInt(FIELD_ELECTRICITY_PRICE_REFRESH_INTERVAL, 0)
    )

    fun toJSONObject(): JSONObject {
        return JSONObject(
            mapOf(
                FIELD_ELECTRICITY_PRICE_ENTITY_ID to entityId,
                FIELD_ELECTRICITY_PRICE_CURRENT_PRICE_ENTITY_ID to currentPriceEntityId,
                FIELD_ELECTRICITY_PRICE_REFRESH_INTERVAL to refreshInterval
            )
        )
    }
}

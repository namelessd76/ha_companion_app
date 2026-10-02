package io.homeassistant.companion.android.tiles

import android.content.Context
import android.util.Log
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_BOLD
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_START
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_TOP
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.R
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.prefs.WearPrefsRepository
import io.homeassistant.companion.android.common.data.prefs.impl.entities.ElectricityPriceTileConfig
import io.homeassistant.companion.android.common.data.servers.ServerManager
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/** A single price entry (typically a 15-minute interval) parsed from a forecast entity's attributes. */
data class HourlyPrice(val time: Instant, val price: Double)

/** Describes where to look for interval price data on an entity's attributes. */
private data class ForecastSource(
    val attributeKeys: List<String>,
    val timeKeys: List<String>,
    val priceKeys: List<String>
)

/**
 * Known shapes of electricity price forecasts, tried in order. The first source that yields data
 * on the entity is used. This lets the tile work out of the box with the most common electricity
 * price integrations without requiring the user to type attribute names on the watch.
 */
private val FORECAST_SOURCES = listOf(
    // HA
    ForecastSource(listOf("data"), listOf("DateTime"), listOf("TotalPrice")),
    // Nord Pool integration: 'raw_today'/'raw_tomorrow', each a list of {start, end, value}
    ForecastSource(listOf("raw_today", "raw_tomorrow"), listOf("start"), listOf("value")),
    // Generic: a single 'forecast' attribute, a list of {start/time, price/value}
    ForecastSource(listOf("forecast"), listOf("start", "time"), listOf("price", "value")),
    // Tibber-style: 'today'/'tomorrow', each a list of {startsAt, total}
    ForecastSource(listOf("today", "tomorrow"), listOf("startsAt", "start"), listOf("total", "price"))
)

private const val PRICE_LOW_MAX = 15.0 // below this: green
private const val PRICE_MEDIUM_MAX = 20.0 // below this: orange, at/above: red

private const val COLOR_LOW = 0xFF43A047.toInt() // green
private const val COLOR_MEDIUM = 0xFFFB8C00.toInt() // orange
private const val COLOR_HIGH = 0xFFE53935.toInt() // red

private const val WINDOW_HOURS = 12L
private const val AXIS_STEP = 10.0 // c/kWh between value axis ticks
private const val LABEL_EVERY_N_HOURS = 2 // timeline labels are shown only for every other hour

// Bar chart dimensions (dp). Bar width/spacing are computed at runtime from the device's screen
// width (e.g. ~195-240dp for a ~488x488px round display, depending on density) so a dense trend of
// 15-minute bars over 12 hours (up to 48 bars) always fits.
private const val BAR_AREA_HEIGHT_DP = 88f
private const val BAR_MIN_HEIGHT_DP = 2f
private const val BAR_MIN_WIDTH_DP = 1.2f
private const val BAR_MIN_SPACING_DP = 0.4f
private const val BAR_SPACING_RATIO = 0.35f // spacing between bars, relative to bar width
private const val USABLE_WIDTH_FRACTION = 0.90f // fraction of screen width usable across a round display's center

// Value axis (shown on both sides of the trend, from 0 to a max rounded up to the next AXIS_STEP)
private const val AXIS_LABEL_WIDTH_DP = 14f
private const val AXIS_GAP_DP = 3f
private const val AXIS_LABEL_LINE_HEIGHT_DP = 10f
private const val AXIS_FONT_SIZE_SP = 7f
private const val HOUR_LABEL_FONT_SIZE_SP = 8f

// Background grid, drawn under the bars: a dark gray horizontal line for every price tag on the
// value axis, and a dark gray vertical separator at the start of every hour.
private const val GRID_LINE_COLOR = 0xFF444444.toInt() // darkgray
private const val GRID_LINE_THICKNESS_DP = 1f
private const val HOUR_SEPARATOR_COLOR = 0xFF444444.toInt() // darkgray
private const val HOUR_SEPARATOR_THICKNESS_DP = 1f

/**
 * Displays the electricity price trend for the next [WINDOW_HOURS] hours as a row of colored bars
 * (one per reported interval, typically 15 minutes), using the entity configured in
 * [WearPrefsRepository.getElectricityPriceTileConfig].
 *
 * Bars (and the optional current price headline) are colored green/orange/red depending on
 * whether the price is below [PRICE_LOW_MAX], below [PRICE_MEDIUM_MAX], or at/above it. A value
 * axis is drawn on both ends of the trend, from 0 to the trend's max (rounded up), in steps of
 * [AXIS_STEP]. The timeline below the bars is labelled every [LABEL_EVERY_N_HOURS] hours, hour only.
 */
@AndroidEntryPoint
class ElectricityPriceTile : TileService() {

    companion object {
        private const val TAG = "ElectricityPriceTile"

        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(ElectricityPriceTile::class.java)
        }

        /** @return the green/orange/red color for [price], in c/kWh (or the entity's own currency unit). */
        fun colorForPrice(price: Double): Int = when {
            price <= PRICE_LOW_MAX -> COLOR_LOW
            price <= PRICE_MEDIUM_MAX -> COLOR_MEDIUM
            else -> COLOR_HIGH
        }

        /**
         * Parses the price forecast out of an entity's [attributes], trying each known
         * [FORECAST_SOURCES] shape in turn and returning the first that yields data, sorted by time.
         */
        fun extractHourlyPrices(attributes: Map<String, Any>): List<HourlyPrice> {
            for (source in FORECAST_SOURCES) {
                val combined = source.attributeKeys.flatMap { key ->
                    parseHourlyList(attributes[key], source.timeKeys, source.priceKeys)
                }
                if (combined.isNotEmpty()) return combined.sortedBy { it.time }
            }
            return emptyList()
        }

        /**
         * @return the entries from [all] covering the [WINDOW_HOURS]-long window starting at the
         *   entry that covers [now]. Works regardless of the source's reporting interval (e.g. 15
         *   minutes or hourly).
         */
        fun nextWindow(all: List<HourlyPrice>, now: Instant = Instant.now().truncatedTo(ChronoUnit.HOURS), windowHours: Long = WINDOW_HOURS): List<HourlyPrice> {
            if (all.isEmpty()) return emptyList()
            val startIndex = all.indexOfLast { it.time <= now }.let { if (it == -1) 0 else it }
            val windowEnd = all[startIndex].time.plus(Duration.ofHours(windowHours))
            return all.subList(startIndex, all.size).takeWhile { it.time < windowEnd }
        }

        /** @return the top value axis tick: the trend's max price rounded up to the next [AXIS_STEP]. */
        fun axisMaxFor(prices: List<HourlyPrice>): Int {
            val maxPrice = prices.maxOfOrNull { it.price } ?: 0.0
            val rounded = (ceil(maxPrice / AXIS_STEP) * AXIS_STEP).toInt()
            return rounded.coerceAtLeast(AXIS_STEP.toInt())
        }

        /**
         * @return the (barWidth, spacing) dp to use for [barCount] bars so the whole chart -
         *   including the value axis on both sides - fits within [screenWidthDp].
         */
        fun barMetrics(screenWidthDp: Int, barCount: Int): Pair<Float, Float> {
            if (barCount <= 0) return BAR_MIN_WIDTH_DP to BAR_MIN_SPACING_DP
            val usableWidth = screenWidthDp * USABLE_WIDTH_FRACTION
            val axisOverhead = 2 * (AXIS_LABEL_WIDTH_DP + AXIS_GAP_DP)
            val barsAreaWidth = (usableWidth - axisOverhead).coerceAtLeast(barCount * BAR_MIN_WIDTH_DP)
            val spacingUnits = (barCount - 1) * BAR_SPACING_RATIO
            val barWidth = (barsAreaWidth / (barCount + spacingUnits)).coerceAtLeast(BAR_MIN_WIDTH_DP)
            val spacing = (barWidth * BAR_SPACING_RATIO).coerceAtLeast(BAR_MIN_SPACING_DP)
            return barWidth to spacing
        }

        private fun parseHourlyList(raw: Any?, timeKeys: List<String>, priceKeys: List<String>): List<HourlyPrice> {
            val list = raw as? List<*> ?: return emptyList()
            return list.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val time = timeKeys.firstNotNullOfOrNull { key -> parseInstant(map[key]) } ?: return@mapNotNull null
                val price = priceKeys.firstNotNullOfOrNull { key -> parsePrice(map[key]) } ?: return@mapNotNull null
                HourlyPrice(time, price)
            }
        }

        private fun parseInstant(value: Any?): Instant? {
            val text = value as? String ?: return null
            return runCatching { OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toInstant() }
                .recoverCatching { Instant.parse(text) }
                .getOrNull()
        }

        private fun parsePrice(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Inject
    lateinit var serverManager: ServerManager

    @Inject
    lateinit var wearPrefsRepository: WearPrefsRepository

    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<Tile> =
        serviceScope.future {
            if (requestParams.currentState.lastClickableId == MODIFIER_CLICK_REFRESH) {
                if (wearPrefsRepository.getWearHapticFeedback()) hapticClick(applicationContext)
            }

            val config = wearPrefsRepository.getElectricityPriceTileConfig()
            val freshness = when {
                config.refreshInterval <= 1 -> 0
                else -> config.refreshInterval
            }

            Tile.Builder()
                .setResourcesVersion("1")
                .setFreshnessIntervalMillis(freshness.toLong() * 1000)
                .setTileTimeline(
                    if (serverManager.isRegistered()) {
                        timeline(config, requestParams.deviceConfiguration.screenWidthDp)
                    } else {
                        loggedOutTimeline(
                            this@ElectricityPriceTile,
                            requestParams,
                            commonR.string.electricity_price_tile,
                            commonR.string.electricity_price_tile_log_in
                        )
                    }
                ).build()
        }

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<Resources> =
        serviceScope.future {
            Resources.Builder()
                .setVersion("1")
                .addIdToImageMapping(
                    RESOURCE_REFRESH,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_refresh)
                                .build()
                        ).build()
                )
                .build()
        }

    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        serviceScope.launch {
            val config = wearPrefsRepository.getElectricityPriceTileConfig()
            if (config.refreshInterval >= 1) {
                try {
                    getUpdater(this@ElectricityPriceTile).requestUpdate(ElectricityPriceTile::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to request tile update on enter", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private suspend fun timeline(config: ElectricityPriceTileConfig, screenWidthDp: Int): Timeline {
        val forecastEntity = getEntityOrNull(config.entityId)
        val allPrices = forecastEntity?.let { extractHourlyPrices(it.attributes) }.orEmpty()
        val upcoming = nextWindow(allPrices)

        val currentPriceEntity = getEntityOrNull(config.currentPriceEntityId)
        val currentPrice = currentPriceEntity?.state?.toDoubleOrNull() ?: upcoming.firstOrNull()?.price

        val unit = (currentPriceEntity?.attributes?.get("unit_of_measurement") as? String)
            ?: (forecastEntity?.attributes?.get("unit_of_measurement") as? String)
            ?: ""

        return Timeline.fromLayoutElement(layout(config.entityId.isBlank(), currentPrice, unit, upcoming, screenWidthDp))
    }

    private suspend fun getEntityOrNull(entityId: String): Entity<Map<String, Any>>? {
        if (entityId.isBlank()) return null
        return try {
            serverManager.integrationRepository().getEntity(entityId)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to get entity $entityId", e)
            null
        }
    }

    fun layout(
        requiresSetup: Boolean,
        currentPrice: Double?,
        unit: String,
        prices: List<HourlyPrice>,
        screenWidthDp: Int
    ): LayoutElement = Box.Builder().apply {
        addContent(
            Column.Builder().apply {
                setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                if (requiresSetup || prices.isEmpty()) {
                    addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(
                                getString(
                                    if (requiresSetup) {
                                        commonR.string.electricity_price_tile_no_entity_yet
                                    } else {
                                        commonR.string.electricity_price_tile_no_data
                                    }
                                )
                            )
                            .setMaxLines(4)
                            .build()
                    )
                } else {
                    if (currentPrice != null) {
                        addContent(headlineText(currentPrice, unit))
                        addContent(Spacer.Builder().setHeight(dp(6f)).build())
                    }
                    val (barWidth, spacing) = barMetrics(screenWidthDp, prices.size)
                    addContent(chartRow(prices, barWidth, spacing))
                    addContent(Spacer.Builder().setHeight(dp(4f)).build())
                    addContent(hourLabelsRow(prices, barWidth, spacing))
                }
            }.build()
        )
        addContent(getRefreshButton())
        setModifiers(getRefreshModifiers())
    }
        .build()

    private fun headlineText(currentPrice: Double, unit: String): LayoutElement {
        val text = formatPrice(currentPrice) + if (unit.isNotBlank()) " $unit" else ""
        return LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(20f))
                    .setWeight(FONT_WEIGHT_BOLD)
                    .setColor(argb(colorForPrice(currentPrice)))
                    .build()
            )
            .build()
    }

    /** The bar chart with a value axis (0..max, step [AXIS_STEP]) on both ends of the trend. */
    private fun chartRow(prices: List<HourlyPrice>, barWidth: Float, spacing: Float): LayoutElement {
        val axisMax = axisMaxFor(prices)
        return Row.Builder()
            .addContent(axisColumn(axisMax))
            .addContent(Spacer.Builder().setWidth(dp(AXIS_GAP_DP)).build())
            .addContent(chartArea(prices, axisMax, barWidth, spacing))
            .addContent(Spacer.Builder().setWidth(dp(AXIS_GAP_DP)).build())
            .addContent(axisColumn(axisMax))
            .build()
    }

    /**
     * The bars, stacked on top of the background grid (horizontal price-tag lines and vertical
     * hour separators) so the grid is drawn first and sits behind/under the bars.
     */
    private fun chartArea(prices: List<HourlyPrice>, axisMax: Int, barWidth: Float, spacing: Float): LayoutElement {
        val totalWidth = prices.size * barWidth + (prices.size - 1) * spacing
        return Box.Builder()
            .setWidth(dp(totalWidth))
            .setHeight(dp(BAR_AREA_HEIGHT_DP))
            .addContent(gridLinesLayer(axisMax, totalWidth))
            .addContent(hourSeparatorsLayer(prices, barWidth, spacing))
            .addContent(barsRow(prices, axisMax, barWidth, spacing))
            .build()
    }

    /** A dark gray horizontal line for every price tag on the value axis (ticks above 0), evenly spaced. */
    private fun gridLinesLayer(axisMax: Int, width: Float): LayoutElement {
        val stepCount = (axisMax / AXIS_STEP.toInt()).coerceAtLeast(1)
        val slotHeight = BAR_AREA_HEIGHT_DP / stepCount
        return Column.Builder().apply {
            repeat(stepCount) {
                addContent(
                    Box.Builder()
                        .setWidth(dp(width))
                        .setHeight(dp(slotHeight))
                        .setVerticalAlignment(VERTICAL_ALIGN_TOP)
                        .addContent(gridLine(width))
                        .build()
                )
            }
        }.build()
    }

    private fun gridLine(width: Float): LayoutElement =
        Box.Builder()
            .setWidth(dp(width))
            .setHeight(dp(GRID_LINE_THICKNESS_DP))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(GRID_LINE_COLOR)).build()
                    )
                    .build()
            )
            .build()

    /** A dark gray vertical separator line at the start of every hour. */
    private fun hourSeparatorsLayer(prices: List<HourlyPrice>, barWidth: Float, spacing: Float): LayoutElement {
        val zone = ZoneId.systemDefault()
        val groups = prices.groupBy { it.time.atZone(zone).truncatedTo(ChronoUnit.HOURS) }.toList()

        return Row.Builder().apply {
            groups.forEachIndexed { groupIndex, (_, entriesInHour) ->
                if (groupIndex > 0) addContent(Spacer.Builder().setWidth(dp(spacing)).build())
                val groupWidth = entriesInHour.size * barWidth + (entriesInHour.size - 1) * spacing
                addContent(
                    Box.Builder()
                        .setWidth(dp(groupWidth))
                        .setHeight(dp(BAR_AREA_HEIGHT_DP))
                        .setHorizontalAlignment(HORIZONTAL_ALIGN_START)
                        .addContent(verticalSeparatorLine())
                        .build()
                )
            }
        }.build()
    }

    private fun verticalSeparatorLine(): LayoutElement =
        Box.Builder()
            .setWidth(dp(HOUR_SEPARATOR_THICKNESS_DP))
            .setHeight(dp(BAR_AREA_HEIGHT_DP))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(HOUR_SEPARATOR_COLOR)).build()
                    )
                    .build()
            )
            .build()

    private fun axisColumn(axisMax: Int): LayoutElement {
        val ticks = (axisMax downTo 0 step AXIS_STEP.toInt()).toList()
        val stepCount = ticks.size - 1
        val gapHeight = if (stepCount > 0) {
            ((BAR_AREA_HEIGHT_DP - ticks.size * AXIS_LABEL_LINE_HEIGHT_DP) / stepCount).coerceAtLeast(0f)
        } else {
            0f
        }

        return Box.Builder()
            .setWidth(dp(AXIS_LABEL_WIDTH_DP))
            .setHeight(dp(BAR_AREA_HEIGHT_DP))
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(
                Column.Builder().apply {
                    ticks.forEachIndexed { index, tick ->
                        addContent(axisTickText(tick))
                        if (index < ticks.lastIndex) addContent(Spacer.Builder().setHeight(dp(gapHeight)).build())
                    }
                }.build()
            )
            .build()
    }

    private fun axisTickText(value: Int): LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(value.toString())
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(AXIS_FONT_SIZE_SP)).build())
            .build()

    /** One bar per entry in [prices], scaled against the fixed 0..[axisMax] value axis. */
    private fun barsRow(prices: List<HourlyPrice>, axisMax: Int, barWidth: Float, spacing: Float): LayoutElement =
        Row.Builder().apply {
            prices.forEachIndexed { index, entry ->
                if (index > 0) addContent(Spacer.Builder().setWidth(dp(spacing)).build())
                val ratio = (entry.price / axisMax).coerceIn(0.0, 1.0)
                val barHeight = BAR_MIN_HEIGHT_DP + ratio.toFloat() * (BAR_AREA_HEIGHT_DP - BAR_MIN_HEIGHT_DP)
                addContent(
                    Box.Builder()
                        .setWidth(dp(barWidth))
                        .setHeight(dp(BAR_AREA_HEIGHT_DP))
                        .setVerticalAlignment(VERTICAL_ALIGN_BOTTOM)
                        .addContent(
                            Box.Builder()
                                .setWidth(dp(barWidth))
                                .setHeight(dp(barHeight))
                                .setModifiers(
                                    ModifiersBuilders.Modifiers.Builder()
                                        .setBackground(
                                            ModifiersBuilders.Background.Builder()
                                                .setColor(argb(colorForPrice(entry.price)))
                                                .build()
                                        )
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
            }
        }.build()

    /**
     * Hour-only labels under the bars (minutes omitted), shown every [LABEL_EVERY_N_HOURS] hours,
     * aligned under the start of their hour, plus one extra trailing label marking the end of the
     * very last bar (e.g. bars ending at 13:45 also get a "14" label). Offset on both sides to
     * line up under [barsRow] rather than under the value axis columns.
     */
    private fun hourLabelsRow(prices: List<HourlyPrice>, barWidth: Float, spacing: Float): LayoutElement {
        val zone = ZoneId.systemDefault()
        val formatter = DateTimeFormatter.ofPattern("HH")
        val groups: List<Pair<ZonedDateTime, List<HourlyPrice>>> = prices
            .groupBy { it.time.atZone(zone).truncatedTo(ChronoUnit.HOURS) }
            .toList()

        val bars = Row.Builder().apply {
            groups.forEachIndexed { groupIndex, (hourStart, entriesInHour) ->
                if (groupIndex > 0) addContent(Spacer.Builder().setWidth(dp(spacing)).build())
                val groupWidth = entriesInHour.size * barWidth + (entriesInHour.size - 1) * spacing
                addContent(
                    Box.Builder()
                        .setWidth(dp(groupWidth * 2))
                        .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                        .apply {
                            if (groupIndex % LABEL_EVERY_N_HOURS == 0) {
                                addContent(smallText(formatter.format(hourStart)))
                            }
                        }
                        .build()
                )
            }
            // Trailing label for the end of the trend's last bar (e.g. bars ending at 13:45 also
            // get a "14" label, marking the end of that last interval), since that boundary isn't
            // the start of any hour group and so isn't covered by the loop above.
            groups.lastOrNull()?.let { (lastHourStart, lastEntries) ->
                addContent(Spacer.Builder().setWidth(dp(spacing)).build())
                val lastGroupWidth = lastEntries.size * barWidth + (lastEntries.size - 1) * spacing
                addContent(
                    Box.Builder()
                        .setWidth(dp(lastGroupWidth * 2))
                        .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                        .addContent(smallText(formatter.format(lastHourStart.plusHours(1))))
                        .build()
                )
            }
        }.build()

        val axisOffset = Box.Builder().setWidth(dp(AXIS_LABEL_WIDTH_DP + AXIS_GAP_DP)).build()
        return Row.Builder()
            .addContent(axisOffset)
            .addContent(bars)
            .addContent(axisOffset)
            .build()
    }

    private fun smallText(text: String): LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(HOUR_LABEL_FONT_SIZE_SP)).build())
            .build()

    private fun formatPrice(price: Double): String = String.format(Locale.getDefault(), "%.2f", price)
}

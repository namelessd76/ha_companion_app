package io.homeassistant.companion.android.tiles

import android.content.Context
import android.util.Log
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.ContainerDimension
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.weight
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_BOLD
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
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
data class HourlyPrice(val time: ZonedDateTime, val price: Double)

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
)

private const val PRICE_LOW_MAX = 15.0 // below this: green
private const val PRICE_MEDIUM_MAX = 20.0 // below this: orange, at/above: red

private const val COLOR_LOW = 0xFF43A047.toInt() // green
private const val COLOR_MEDIUM = 0xFFFB8C00.toInt() // orange
private const val COLOR_HIGH = 0xFFE53935.toInt() // red

private const val WINDOW_HOURS = 12L
private const val AXIS_STEP = 5.0 // c/kWh between value axis ticks
private const val LABEL_EVERY_N_HOURS = 2 // timeline labels are shown only for every other hour

// Bar chart dimensions. Bars and the gaps between them are all sized with weighted expand()
// dimensions (see barsRow), so the renderer itself divides up whatever width is actually
// available (accounting for round-screen clipping etc.) instead of us trying to pre-compute it in
// dp from screenWidthDp, which can never exactly match the real available space.
private const val BAR_AREA_HEIGHT_DP = 88f
private const val BAR_MIN_HEIGHT_DP = 2f
private const val BAR_SPACING_RATIO = 0.35f // gap weight, relative to a bar's weight of 1

// Value axis (shown on both sides of the trend, from 0 to a max rounded up to the next AXIS_STEP)
private const val AXIS_LABEL_WIDTH_DP = 14f
private const val AXIS_GAP_DP = 3f
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
        fun nextWindow(all: List<HourlyPrice>, now: ZonedDateTime = Instant.now().truncatedTo(ChronoUnit.HOURS).atZone(ZoneId.systemDefault()), windowHours: Long = WINDOW_HOURS): List<HourlyPrice> {
            if (all.isEmpty()) return emptyList()
            val startIndex = all.indexOfLast { it.time <= now }.let { if (it == -1) 0 else it }
            val windowEnd = all[startIndex].time.plus(Duration.ofHours(windowHours))
            return all.subList(startIndex, all.size).takeWhile { it.time < windowEnd }
        }

        /** @return the top value axis tick: the trend's max price rounded up to the next [AXIS_STEP]. */
        fun axisMaxFor(prices: List<HourlyPrice>): Pair<Int, Double> {
            val maxPrice = prices.maxOfOrNull { it.price } ?: 0.0
            var axisStep = AXIS_STEP;
            while (maxPrice / axisStep > 5)
            {
                axisStep += AXIS_STEP;
            }
            val rounded = (ceil(maxPrice / axisStep) * axisStep).toInt()
            return rounded.coerceAtLeast(axisStep.toInt()) to axisStep
        }

        private fun parseHourlyList(raw: Any?, timeKeys: List<String>, priceKeys: List<String>): List<HourlyPrice> {
            val list = raw as? List<*> ?: return emptyList()
            return list.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val time = timeKeys.firstNotNullOfOrNull { key -> parseTime(map[key]) } ?: return@mapNotNull null
                val price = priceKeys.firstNotNullOfOrNull { key -> parsePrice(map[key]) } ?: return@mapNotNull null
                HourlyPrice(time, price)
            }
        }

        private fun parseTime(value: Any?): ZonedDateTime? {
            val text = value as? String ?: return null
            return runCatching { OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()) }
                .recoverCatching { ZonedDateTime.parse(text) }
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
                        timeline(config)
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

    private suspend fun timeline(config: ElectricityPriceTileConfig): Timeline {
        val forecastEntity = getEntityOrNull(config.entityId)
        val allPrices = forecastEntity?.let { extractHourlyPrices(it.attributes) }.orEmpty()
        val upcoming = nextWindow(allPrices)

        val currentPriceEntity = getEntityOrNull(config.currentPriceEntityId)
        val currentPrice = currentPriceEntity?.state?.toDoubleOrNull() ?: upcoming.firstOrNull()?.price

        val unit = (currentPriceEntity?.attributes?.get("unit_of_measurement") as? String)
            ?: (forecastEntity?.attributes?.get("unit_of_measurement") as? String)
            ?: ""

        return Timeline.fromLayoutElement(layout(config.entityId.isBlank(), currentPrice, unit, upcoming))
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
        prices: List<HourlyPrice>
    ): LayoutElement = Box.Builder().setWidth(expand()).setHeight(expand()).apply {
        addContent(
            Column.Builder().setWidth(expand()).apply {
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
                    addContent(chartRow(prices))
                    addContent(Spacer.Builder().setHeight(dp(4f)).build())
                    addContent(hourLabelsRow(prices))
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
    private fun chartRow(prices: List<HourlyPrice>): LayoutElement {
        val (axisMax, axisStep) = axisMaxFor(prices)
        return Row.Builder()
            .setWidth(expand())
            .addContent(axisColumn(axisMax, axisStep))
            .addContent(Spacer.Builder().setWidth(dp(AXIS_GAP_DP)).build())
            .addContent(chartArea(prices, axisMax, axisStep))
            .addContent(Spacer.Builder().setWidth(dp(AXIS_GAP_DP)).build())
            .addContent(axisColumn(axisMax, axisStep))
            .build()
    }

    /**
     * The bars, stacked on top of the background grid (horizontal price-tag lines and vertical
     * hour separators) so the grid is drawn first and sits behind/under the bars. Fills whatever
     * width [chartRow] has left over after its two fixed-width axis columns.
     */
    private fun chartArea(prices: List<HourlyPrice>, axisMax: Int, axisStep: Double): LayoutElement =
        Box.Builder()
            .setWidth(expand())
            .setHeight(dp(BAR_AREA_HEIGHT_DP))
            .addContent(gridLinesLayer(axisMax, axisStep))
            .addContent(barsRow(prices, axisMax, axisStep))
            .build()

    /**
     * The value axis ticks, from [axisMax] down to 0 in steps of [axisStep]. [axisColumn] and
     * [gridLinesLayer] both build their Column out of these same equally-sized, centered slots -
     * one slot per tick - so a tick's printed value and its background line always land in
     * exactly the same slot and therefore share the same vertical center, however tall the
     * slot's own content (text vs. a thin line) happens to be.
     */
    private fun axisTicks(axisMax: Int, axisStep: Double): List<Int> =
        (axisMax downTo 0 step axisStep.toInt()).toList()

    private fun axisSlotHeight(tickCount: Int): Float = BAR_AREA_HEIGHT_DP / tickCount

    /** A [width]x[height] slot with [content] centered both horizontally and vertically within it. */
    private fun centeredSlot(width: ContainerDimension, height: Float, content: LayoutElement): LayoutElement =
        Box.Builder()
            .setWidth(width)
            .setHeight(dp(height))
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .addContent(content)
            .build()

    /**
     * A dark gray horizontal line for every price tag on the value axis (0..[axisMax]), evenly
     * spaced, filling whatever width [chartArea] actually has.
     */
    private fun gridLinesLayer(axisMax: Int, axisStep: Double): LayoutElement {
        val ticks = axisTicks(axisMax, axisStep)
        val slotHeight = axisSlotHeight(ticks.size)
        return Column.Builder().setWidth(expand()).apply {
            ticks.forEach { addContent(centeredSlot(expand(), slotHeight, gridLine())) }
        }.build()
    }

    private fun gridLine(): LayoutElement =
        Box.Builder()
            .setWidth(expand())
            .setHeight(dp(GRID_LINE_THICKNESS_DP))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(GRID_LINE_COLOR)).build()
                    )
                    .build()
            )
            .build()

    private fun axisColumn(axisMax: Int, axisStep: Double): LayoutElement {
        val ticks = axisTicks(axisMax, axisStep)
        val slotHeight = axisSlotHeight(ticks.size)

        return Box.Builder()
            .setWidth(dp(AXIS_LABEL_WIDTH_DP))
            .setHeight(dp(BAR_AREA_HEIGHT_DP))
            .addContent(
                Column.Builder().apply {
                    ticks.forEach { tick ->
                        addContent(centeredSlot(dp(AXIS_LABEL_WIDTH_DP), slotHeight, axisTickText(tick)))
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

    /**
     * The bars, plus the hour separators - built together as one alternating sequence of bars and
     * gaps, so both share the exact same per-bar weighting and can never drift out of alignment
     * with each other. There is a gap both before the first bar and after the last, as well as
     * between every pair of bars: [prices].size + 1 gaps in total. Each gap is either empty, or -
     * at an hour boundary - a dark gray vertical separator line, drawn *in* the gap itself rather
     * than behind/under a bar (where it would be invisible).
     *
     * Every bar has a weighted [expand] width of 1, and every gap a weighted width of
     * [BAR_SPACING_RATIO], so the renderer itself divides up whatever width [chartArea] actually
     * ends up with - exactly, with nothing left over to clip and nothing pre-computed in dp that
     * could drift out of sync with the real available space.
     *
     * Bars are scaled against the fixed 0..[axisMax] value axis, but *not* across the full
     * [BAR_AREA_HEIGHT_DP]: since [axisColumn]/[gridLinesLayer] center each tick within its own
     * slot, the "0" and [axisMax] lines sit half a slot in from the box's true top/bottom edges,
     * not right on them. Bars are inset by that same half-slot on both ends (see [edgeInset]) so a
     * price of 0 lines up exactly with the "0" line, and a price of [axisMax] with the top line.
     */
    private fun barsRow(prices: List<HourlyPrice>, axisMax: Int, axisStep: Double): LayoutElement {
        val slotHeight = axisSlotHeight(axisTicks(axisMax, axisStep).size)
        val edgeInset = slotHeight / 2f
        val chartHeight = BAR_AREA_HEIGHT_DP - edgeInset * 2f

        // The reported interval between entries (e.g. 15 minutes), used to tell whether the last
        // bar's interval reaches exactly into the next hour.
        val interval = if (prices.size >= 2) Duration.between(prices[0].time, prices[1].time) else Duration.ZERO
        fun isHourStart(time: ZonedDateTime) = time == time.truncatedTo(ChronoUnit.HOURS)
        fun isHourEnd(time: ZonedDateTime) = interval > Duration.ZERO && isHourStart(time.plus(interval))

        val bars = Row.Builder().setWidth(expand()).apply {
            // Leading gap, before the very first bar.
            addContent(gapOrSeparator(chartHeight, isHourStart(prices.first().time)))
            prices.forEachIndexed { index, entry ->
                val ratio = (entry.price / axisMax).coerceIn(0.0, 1.0)
                val barHeight = BAR_MIN_HEIGHT_DP + ratio.toFloat() * (chartHeight - BAR_MIN_HEIGHT_DP)
                addContent(barCell(chartHeight, barHeight, colorForPrice(entry.price)))
                // The gap after this bar: a boundary if the next bar starts a new hour, or - for
                // the trailing gap after the very last bar - if this bar's own interval reaches
                // exactly into the next hour.
                val showSeparator = if (index < prices.lastIndex) {
                    isHourStart(prices[index + 1].time)
                } else {
                    isHourEnd(entry.time)
                }
                addContent(gapOrSeparator(chartHeight, showSeparator))
            }
        }.build()

        return Column.Builder()
            .setWidth(expand())
            .addContent(Spacer.Builder().setHeight(dp(edgeInset)).build())
            .addContent(bars)
            .addContent(Spacer.Builder().setHeight(dp(edgeInset)).build())
            .build()
    }

    /** A single price bar, weighted 1, bottom-anchored within its [chartHeight]-tall cell. */
    private fun barCell(chartHeight: Float, barHeight: Float, color: Int): LayoutElement =
        Box.Builder()
            .setWidth(weight(1f))
            .setHeight(dp(chartHeight))
            .setVerticalAlignment(VERTICAL_ALIGN_BOTTOM)
            .addContent(
                Box.Builder()
                    .setWidth(expand())
                    .setHeight(dp(barHeight))
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder().setColor(argb(color)).build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    /** A gap between bars, weighted [BAR_SPACING_RATIO]: empty, or - at an hour boundary - a vertical separator line. */
    private fun gapOrSeparator(height: Float, showSeparator: Boolean): LayoutElement =
        if (showSeparator) {
            Box.Builder()
                .setWidth(weight(BAR_SPACING_RATIO))
                .setHeight(dp(height))
                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                .addContent(verticalSeparatorLine(height))
                .build()
        } else {
            Spacer.Builder().setWidth(weight(BAR_SPACING_RATIO)).build()
        }

    private fun verticalSeparatorLine(height: Float): LayoutElement =
        Box.Builder()
            .setWidth(dp(HOUR_SEPARATOR_THICKNESS_DP))
            .setHeight(dp(height))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(HOUR_SEPARATOR_COLOR)).build()
                    )
                    .build()
            )
            .build()

    /**
     * Hour-only labels under the bars (minutes omitted), shown every [LABEL_EVERY_N_HOURS] hours,
     * aligned under the start of their hour, plus one extra trailing label marking the end of the
     * very last bar (e.g. bars ending at 13:45 also get a "14" label). Offset on both sides to
     * line up under [barsRow] rather than under the value axis columns.
     */
    private fun hourLabelsRow(prices: List<HourlyPrice>): LayoutElement {
        val formatter = DateTimeFormatter.ofPattern("HH")
        val groups: List<Pair<ZonedDateTime, List<HourlyPrice>>> = prices
            .groupBy { it.time.truncatedTo(ChronoUnit.HOURS) }
            .toList()

        // Same weights barsRow uses per bar (1) and per gap (BAR_SPACING_RATIO), just summed up
        // per hour-group here, so this row's groups land at the same relative positions as the
        // real bars - however the renderer ends up dividing up the actual available width.
        fun groupWeight(entryCount: Int) = entryCount * 1f + (entryCount - 1) * BAR_SPACING_RATIO

        val bars = Row.Builder().setWidth(expand()).apply {
            groups.forEachIndexed { groupIndex, (hourStart, entriesInHour) ->
                if (groupIndex > 0) addContent(Spacer.Builder().setWidth(weight(BAR_SPACING_RATIO)).build())
                addContent(
                    Box.Builder()
                        .setWidth(weight(groupWeight(entriesInHour.size) * 2))
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
                addContent(Spacer.Builder().setWidth(weight(BAR_SPACING_RATIO)).build())
                addContent(
                    Box.Builder()
                        .setWidth(weight(groupWeight(lastEntries.size) * 2))
                        .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                        .addContent(smallText(formatter.format(lastHourStart.plusHours(1))))
                        .build()
                )
            }
        }.build()

        val axisOffset = Box.Builder().setWidth(dp(AXIS_LABEL_WIDTH_DP + AXIS_GAP_DP)).build()
        return Row.Builder()
            .setWidth(expand())
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

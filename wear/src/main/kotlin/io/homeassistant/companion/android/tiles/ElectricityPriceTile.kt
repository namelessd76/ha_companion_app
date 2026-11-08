package io.homeassistant.companion.android.tiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DeviceParametersBuilders.SCREEN_SHAPE_ROUND
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.InlineImageResource
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
import java.io.ByteArrayOutputStream
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import timber.log.Timber

/** A single price entry (typically a 15-minute interval) parsed from a forecast entity's attributes. */
data class HourlyPrice(val time: ZonedDateTime, val price: Double)

/** Describes where to look for interval price data on an entity's attributes. */
private data class ForecastSource(
    val attributeKeys: List<String>,
    val timeKeys: List<String>,
    val priceKeys: List<String>,
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

private const val GRID_LINE_COLOR = 0xFF444444.toInt() // darkgray
private const val HOUR_SEPARATOR_COLOR = 0xFF444444.toInt() // darkgray
private const val TEXT_COLOR = 0xFFFFFFFF.toInt() // white

// All sizes below are dp; they're converted to real pixels using the device's actual density (and
// font scale, for text) when the chart is drawn - see ChartPainter. Drawing on a canvas, with real
// measured text widths and real circle geometry, means the chart always exactly fits the device's
// actual screen instead of us trying to predict it from screenWidthDp in the layout tree.
private const val BAR_AREA_HEIGHT_DP = 88f
private const val BAR_MIN_HEIGHT_DP = 2f
private const val BAR_SPACING_RATIO = 0.35f // gap width, relative to a bar's width
private const val AXIS_GAP_DP = 3f // gap between the axis labels and the bars
private const val SIDE_PADDING_DP = 2f // small extra padding beyond the computed round-screen-safe margin
private const val HEADLINE_TO_CHART_GAP_DP = 6f
private const val CHART_TO_HOUR_LABELS_GAP_DP = 2f
private const val GRID_LINE_THICKNESS_DP = 1f
private const val HOUR_SEPARATOR_THICKNESS_DP = 1f
private const val HEADLINE_TEXT_SIZE_SP = 20f
private const val AXIS_TEXT_SIZE_SP = 8f
private const val HOUR_LABEL_TEXT_SIZE_SP = 8f

private const val RESOURCE_CHART = "chart"

/**
 * Displays the electricity price trend for the next [WINDOW_HOURS] hours as a row of colored bars
 * (one per reported interval, typically 15 minutes), using the entity configured in
 * [WearPrefsRepository.getElectricityPriceTileConfig].
 *
 * The chart is drawn as a bitmap (see [ChartPainter]) rather than built out of ProtoLayout
 * elements: it needs pixel-exact control over margins (so axis labels never get clipped by a round
 * bezel), vertical centering, and tightly-packed spacing that ProtoLayout's declarative layout
 * model can only approximate. The bitmap is then shown full-size in the tile, with just the
 * refresh button overlaid as a native (clickable) element.
 *
 * Bars (and the current price headline) are colored green/orange/red depending on whether the
 * price is below [PRICE_LOW_MAX], below [PRICE_MEDIUM_MAX], or at/above it. A value axis is drawn
 * on both ends of the trend, from 0 to the trend's max (rounded up), in steps of [AXIS_STEP]. The
 * timeline below the bars is labelled every [LABEL_EVERY_N_HOURS] hours, hour only.
 */
@AndroidEntryPoint
class ElectricityPriceTile : TileService() {

    companion object {
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
        fun extractHourlyPrices(attributes: Map<String, Any?>): List<HourlyPrice> {
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
        fun nextWindow(
            all: List<HourlyPrice>,
            now: ZonedDateTime = Instant.now().atZone(ZoneId.systemDefault()),
            windowHours: Long = WINDOW_HOURS,
        ): List<HourlyPrice> {
            if (all.isEmpty()) return emptyList()
            val startIndex = all.indexOfLast { it.time <= now }.let { if (it == -1) 0 else it }
            val windowEnd = all[startIndex].time.plus(Duration.ofHours(windowHours))
            return all.subList(startIndex, all.size).takeWhile { it.time < windowEnd }
        }

        /** @return the top value axis tick: the trend's max price rounded up to the next [AXIS_STEP]. */
        fun axisMaxFor(prices: List<HourlyPrice>): Pair<Int, Double> {
            val maxPrice = prices.maxOfOrNull { it.price } ?: 0.0
            var axisStep = AXIS_STEP
            while (maxPrice / axisStep > 5) {
                axisStep += AXIS_STEP
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

        /**
         * Fetches the forecast (and current price) entities from [config] and draws the price chart
         * for a screen described by [geometry]. Shared by the tile and [ElectricityPriceActivity].
         */
        suspend fun buildChartBitmap(
            context: Context,
            serverManager: ServerManager,
            config: ElectricityPriceTileConfig,
            geometry: ChartGeometry,
        ): Bitmap {
            val forecastEntity = getEntityOrNull(serverManager, config.entityId)
            val allPrices = forecastEntity?.let { extractHourlyPrices(it.attributes) }.orEmpty()
            val upcoming = nextWindow(allPrices)

            val currentPriceEntity = getEntityOrNull(serverManager, config.currentPriceEntityId)
            val currentPrice = currentPriceEntity?.state?.toDoubleOrNull() ?: upcoming.firstOrNull()?.price

            val unit = (currentPriceEntity?.attributes?.get("unit_of_measurement") as? String)
                ?: (forecastEntity?.attributes?.get("unit_of_measurement") as? String)
                ?: ""

            return ElectricityPriceChartPainter(context, geometry).paint(upcoming, currentPrice, unit)
        }

        private suspend fun getEntityOrNull(serverManager: ServerManager, entityId: String): Entity? {
            if (entityId.isBlank()) return null
            return try {
                serverManager.integrationRepository().getEntity(entityId)
            } catch (e: Exception) {
                Timber.e(e, "Unable to get entity $entityId")
                null
            }
        }
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Inject
    lateinit var serverManager: ServerManager

    @Inject
    lateinit var wearPrefsRepository: WearPrefsRepository

    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<Tile> = serviceScope.future {
        if (requestParams.currentState.lastClickableId == MODIFIER_CLICK_REFRESH) {
            if (wearPrefsRepository.getWearHapticFeedback()) hapticClick(applicationContext)
        }

        val config = wearPrefsRepository.getElectricityPriceTileConfig()
        val freshness = when {
            config.refreshInterval <= 1 -> 0
            else -> config.refreshInterval
        }

        Tile.Builder()
            // The chart bitmap depends on live price data, so always have onTileResourcesRequest
            // regenerate it rather than reusing a cached version.
            .setResourcesVersion(System.currentTimeMillis().toString())
            .setFreshnessIntervalMillis(freshness.toLong() * 1_000)
            .setTileTimeline(
                if (!serverManager.isRegistered()) {
                    loggedOutTimeline(
                        this@ElectricityPriceTile,
                        requestParams,
                        commonR.string.electricity_price_tile,
                        commonR.string.electricity_price_tile_log_in,
                    )
                } else if (config.entityId.isBlank()) {
                    Timeline.fromLayoutElement(requiresSetupLayout())
                } else {
                    Timeline.fromLayoutElement(chartImageLayout())
                },
            ).build()
    }

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<Resources> =
        serviceScope.future {
            val builder = Resources.Builder()
                .setVersion(requestParams.version)
                .addIdToImageMapping(
                    RESOURCE_REFRESH,
                    ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_refresh)
                                .build(),
                        ).build(),
                )

            val config = wearPrefsRepository.getElectricityPriceTileConfig()
            if (serverManager.isRegistered() && config.entityId.isNotBlank()) {
                val bitmap = buildChartBitmap(
                    this@ElectricityPriceTile,
                    serverManager,
                    config,
                    ChartGeometry.from(requestParams.deviceConfiguration),
                )
                val bytes = ByteArrayOutputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.toByteArray()
                }
                builder.addIdToImageMapping(
                    RESOURCE_CHART,
                    ImageResource.Builder()
                        .setInlineResource(
                            InlineImageResource.Builder()
                                .setData(bytes)
                                .setWidthPx(bitmap.width)
                                .setHeightPx(bitmap.height)
                                .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                                .build(),
                        )
                        .build(),
                )
            }

            builder.build()
        }

    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        serviceScope.launch {
            val config = wearPrefsRepository.getElectricityPriceTileConfig()
            if (config.refreshInterval >= 1) {
                try {
                    getUpdater(this@ElectricityPriceTile).requestUpdate(ElectricityPriceTile::class.java)
                } catch (e: Exception) {
                    Timber.w(e, "Unable to request tile update on enter")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun requiresSetupLayout(): LayoutElement = Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
        .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
        .addContent(
            LayoutElementBuilders.Text.Builder()
                .setText(getString(commonR.string.electricity_price_tile_no_entity_yet))
                .setMaxLines(4)
                .build(),
        )
        .addContent(getRefreshButton())
        .setModifiers(getRefreshModifiers())
        .build()

    private fun chartImageLayout(): LayoutElement = Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .addContent(
            LayoutElementBuilders.Image.Builder()
                .setResourceId(RESOURCE_CHART)
                .setWidth(expand())
                .setHeight(expand())
                .build(),
        )
        .addContent(getRefreshButton())
        .setModifiers(getRefreshModifiers())
        .build()
}

/** Screen geometry the price chart is drawn for, in real pixels. */
data class ChartGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val fontScale: Float,
    val isRound: Boolean,
) {
    companion object {
        fun from(device: DeviceParameters): ChartGeometry = ChartGeometry(
            widthPx = (device.screenWidthDp * device.screenDensity).roundToInt(),
            heightPx = (device.screenHeightDp * device.screenDensity).roundToInt(),
            density = device.screenDensity,
            fontScale = device.fontScale,
            isRound = device.screenShape == SCREEN_SHAPE_ROUND,
        )
    }
}

private fun formatPrice(price: Double): String = String.format(Locale.getDefault(), "%.2f", price)

/**
 * Draws the price trend chart onto a bitmap sized to the actual screen, in real pixels,
 * so every measurement (text width, available margin, round-screen clipping) uses the device's
 * real geometry rather than an estimate.
 */
private class ElectricityPriceChartPainter(private val context: Context, geometry: ChartGeometry) {
    private val density = geometry.density
    private val fontScale = geometry.fontScale.takeIf { it > 0f } ?: 1f
    private val widthPx = geometry.widthPx
    private val heightPx = geometry.heightPx
    private val isRound = geometry.isRound
    private val radiusPx = min(widthPx, heightPx) / 2f
    private val centerXPx = widthPx / 2f
    private val centerYPx = heightPx / 2f

    private fun Float.dpToPx() = this * density
    private fun Float.spToPx() = this * density * fontScale

    private fun textPaint(sizeSp: Float, bold: Boolean = false): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        textSize = sizeSp.spToPx()
        isFakeBoldText = bold
        textAlign = Paint.Align.CENTER
    }

    private fun linePaint(color: Int, thicknessDp: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = thicknessDp.dpToPx()
    }

    private fun Canvas.drawCenteredText(text: String, paint: Paint, cx: Float, cy: Float) {
        val fm = paint.fontMetrics
        drawText(text, cx, cy - (fm.descent + fm.ascent) / 2f, paint)
    }

    /** @return the half-width (px) of the screen available at vertical distance [dy] from center. */
    private fun safeHalfWidthAt(dy: Float): Float =
        if (isRound) sqrt(max(radiusPx * radiusPx - dy * dy, 0f)) else widthPx / 2f

    fun paint(prices: List<HourlyPrice>, currentPrice: Double?, unit: String): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val barAreaHeightPx = BAR_AREA_HEIGHT_DP.dpToPx()
        val chartTop = centerYPx - barAreaHeightPx / 2f
        val chartBottom = centerYPx + barAreaHeightPx / 2f

        if (prices.isEmpty()) {
            canvas.drawCenteredText(
                context.getString(commonR.string.electricity_price_tile_no_data),
                textPaint(HOUR_LABEL_TEXT_SIZE_SP),
                centerXPx,
                centerYPx,
            )
            return bitmap
        }

        val (axisMax, axisStep) = ElectricityPriceTile.axisMaxFor(prices)
        val axisPaint = textPaint(AXIS_TEXT_SIZE_SP)
        val ticks = (axisMax downTo 0 step axisStep.toInt()).toList()
        val axisLabelWidthPx = ticks.maxOf { axisPaint.measureText(it.toString()) }

        // The round screen narrows away from the vertical center, so use the tightest available
        // half-width across the chart's own vertical span (its top/bottom-most content) as the
        // safe margin for everything - conservative, but guarantees nothing gets clipped.
        val safeHalfWidthPx = min(safeHalfWidthAt(chartTop - centerYPx), safeHalfWidthAt(chartBottom - centerYPx))
        val sideMarginPx = (widthPx / 2f - safeHalfWidthPx) + SIDE_PADDING_DP.dpToPx()

        val barsAreaLeft = sideMarginPx + axisLabelWidthPx + AXIS_GAP_DP.dpToPx()
        val barsAreaRight = widthPx - sideMarginPx - axisLabelWidthPx - AXIS_GAP_DP.dpToPx()
        val barsAreaWidthPx = (barsAreaRight - barsAreaLeft).coerceAtLeast(0f)

        val barCount = prices.size
        val spacingUnits = (barCount + 1) * BAR_SPACING_RATIO
        val barWidthPx = barsAreaWidthPx / (barCount + spacingUnits)
        val spacingPx = barWidthPx * BAR_SPACING_RATIO

        drawHeadline(canvas, currentPrice, unit, chartTop)
        drawGridLines(canvas, ticks, axisMax, barsAreaLeft, barsAreaRight, chartTop, chartBottom)
        drawAxisLabels(canvas, ticks, axisMax, axisPaint, sideMarginPx, axisLabelWidthPx, chartTop, chartBottom)
        drawHourSeparators(canvas, prices, barsAreaLeft, barWidthPx, spacingPx, chartTop, chartBottom)
        drawBars(canvas, prices, axisMax, barsAreaLeft, barWidthPx, spacingPx, chartBottom, barAreaHeightPx)
        drawHourLabels(canvas, prices, barsAreaLeft, barWidthPx, spacingPx, chartBottom)

        return bitmap
    }

    private fun drawHeadline(canvas: Canvas, currentPrice: Double?, unit: String, chartTop: Float) {
        if (currentPrice == null) return
        val text = formatPrice(currentPrice) + if (unit.isNotBlank()) " $unit" else ""
        val paint = textPaint(HEADLINE_TEXT_SIZE_SP, bold = true).apply {
            color = ElectricityPriceTile.colorForPrice(currentPrice)
        }
        val fm = paint.fontMetrics
        // Position the text so its descent line (visual bottom) sits HEADLINE_TO_CHART_GAP_DP above chartTop.
        val baseline = (chartTop - HEADLINE_TO_CHART_GAP_DP.dpToPx()) - fm.descent
        canvas.drawText(text, centerXPx, baseline, paint)
    }

    /** A dark gray horizontal line for every price tag on the value axis above 0, at its exact linear position. */
    private fun drawGridLines(
        canvas: Canvas,
        ticks: List<Int>,
        axisMax: Int,
        left: Float,
        right: Float,
        chartTop: Float,
        chartBottom: Float,
    ) {
        val paint = linePaint(GRID_LINE_COLOR, GRID_LINE_THICKNESS_DP)
        ticks.filter { it != 0 }.forEach { tick ->
            val y = chartBottom - (tick.toFloat() / axisMax) * (chartBottom - chartTop)
            canvas.drawLine(left, y, right, y, paint)
        }
    }

    private fun drawAxisLabels(
        canvas: Canvas,
        ticks: List<Int>,
        axisMax: Int,
        paint: Paint,
        sideMarginPx: Float,
        axisLabelWidthPx: Float,
        chartTop: Float,
        chartBottom: Float,
    ) {
        val leftX = sideMarginPx + axisLabelWidthPx / 2f
        val rightX = widthPx - sideMarginPx - axisLabelWidthPx / 2f
        ticks.forEach { tick ->
            val y = chartBottom - (tick.toFloat() / axisMax) * (chartBottom - chartTop)
            canvas.drawCenteredText(tick.toString(), paint, leftX, y)
            canvas.drawCenteredText(tick.toString(), paint, rightX, y)
        }
    }

    /** One bar per entry in [prices], scaled against the fixed 0..[axisMax] value axis. */
    private fun drawBars(
        canvas: Canvas,
        prices: List<HourlyPrice>,
        axisMax: Int,
        barsAreaLeft: Float,
        barWidthPx: Float,
        spacingPx: Float,
        chartBottom: Float,
        barAreaHeightPx: Float,
    ) {
        val minBarHeightPx = BAR_MIN_HEIGHT_DP.dpToPx()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val interval = if (prices.size >= 2) Duration.between(prices[0].time, prices[1].time) else Duration.ZERO
        prices.forEachIndexed { index, entry ->
            val left = barLeft(barsAreaLeft, barWidthPx, spacingPx, index)
            val ratio = (entry.price / axisMax).coerceIn(0.0, 1.0)
            val barHeightPx = minBarHeightPx + ratio.toFloat() * (barAreaHeightPx - minBarHeightPx)
            paint.color = ElectricityPriceTile.colorForPrice(entry.price)
            canvas.drawRect(left, chartBottom - barHeightPx, left + barWidthPx, chartBottom, paint)
        }
    }

    /**
     * A dark gray vertical separator at the start of every hour (including the very first bar,
     * if it lands exactly on the hour), plus one trailing separator after the very last bar if
     * its interval reaches exactly into the next hour. Drawn in the gap between bars, not
     * behind/under a bar, so it's never hidden.
     */
    private fun drawHourSeparators(
        canvas: Canvas,
        prices: List<HourlyPrice>,
        barsAreaLeft: Float,
        barWidthPx: Float,
        spacingPx: Float,
        chartTop: Float,
        chartBottom: Float,
    ) {
        val interval = if (prices.size >= 2) Duration.between(prices[0].time, prices[1].time) else Duration.ZERO
        fun isHourStart(time: ZonedDateTime) = time == time.truncatedTo(ChronoUnit.HOURS)
        fun isHourEnd(time: ZonedDateTime) = interval > Duration.ZERO && isHourStart(time.plus(interval))

        val paint = linePaint(HOUR_SEPARATOR_COLOR, HOUR_SEPARATOR_THICKNESS_DP)
        fun drawAt(x: Float) = canvas.drawLine(x, chartTop, x, chartBottom, paint)

        if (isHourStart(prices.first().time)) {
            drawAt(barLeft(barsAreaLeft, barWidthPx, spacingPx, 0) - spacingPx / 2f)
        }
        prices.forEachIndexed { index, entry ->
            val showSeparator = if (index < prices.lastIndex) {
                isHourStart(prices[index + 1].time)
            } else {
                isHourEnd(entry.time)
            }
            if (showSeparator) {
                drawAt(barLeft(barsAreaLeft, barWidthPx, spacingPx, index) + barWidthPx + spacingPx / 2f)
            }
        }
    }

    /**
     * Hour-only labels under the bars (minutes omitted), shown every [LABEL_EVERY_N_HOURS]
     * hours, centered under the start of their hour, plus one extra trailing label marking the
     * end of the very last bar (e.g. bars ending at 13:45 also get a "14" label).
     */
    private fun drawHourLabels(
        canvas: Canvas,
        prices: List<HourlyPrice>,
        barsAreaLeft: Float,
        barWidthPx: Float,
        spacingPx: Float,
        chartBottom: Float,
    ) {
        val formatter = DateTimeFormatter.ofPattern("HH")
        val paint = textPaint(HOUR_LABEL_TEXT_SIZE_SP)
        val fm = paint.fontMetrics
        val y = chartBottom + CHART_TO_HOUR_LABELS_GAP_DP.dpToPx() - fm.ascent

        val groups: List<Pair<ZonedDateTime, List<HourlyPrice>>> = prices
            .groupBy { it.time.truncatedTo(ChronoUnit.HOURS) }
            .toList()

        var barIndex = 0
        var grpIndex = 0
        groups.forEachIndexed { groupIndex, (hourStart, entriesInHour) ->
            if ((groupIndex == 0) && (entriesInHour.size != 4)) {
                grpIndex = 1
            } else if ((groupIndex + grpIndex) % LABEL_EVERY_N_HOURS == 0) {
                val groupLeft = barLeft(barsAreaLeft, barWidthPx, spacingPx, barIndex)
                canvas.drawText(formatter.format(hourStart), groupLeft, y, paint)
            }
            barIndex += entriesInHour.size
        }
        // Trailing label for the end of the trend's last bar.
        if ((groups.size + grpIndex) % LABEL_EVERY_N_HOURS == 0) {
            groups.lastOrNull()?.let { (lastHourStart, entriesInHour) ->
                if (entriesInHour.size == 4) {
                    val lastBarRight = barLeft(barsAreaLeft, barWidthPx, spacingPx, prices.lastIndex) + barWidthPx
                    canvas.drawText(
                        formatter.format(lastHourStart.plusHours(1)),
                        lastBarRight + spacingPx,
                        y,
                        paint,
                    )
                }
            }
        }
    }

    private fun barLeft(barsAreaLeft: Float, barWidthPx: Float, spacingPx: Float, index: Int): Float =
        barsAreaLeft + spacingPx + index * (barWidthPx + spacingPx)
}

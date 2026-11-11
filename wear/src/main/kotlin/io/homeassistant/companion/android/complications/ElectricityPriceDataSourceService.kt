package io.homeassistant.companion.android.complications

import android.graphics.Color
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dagger.hilt.android.AndroidEntryPoint
import io.github.timoptr.mdiicons.Mdi
import io.github.timoptr.mdiicons.generated.Flash
import io.github.timoptr.mdiicons.toBitmap
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.tiles.ElectricityPriceForecastRepository
import io.homeassistant.companion.android.tiles.ElectricityPriceForecastSchedule
import io.homeassistant.companion.android.tiles.ElectricityPriceTile
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import kotlin.time.Clock

private const val ICON_SIZE_DP = 24
private const val PREVIEW_PRICE = 4.28f
private const val PREVIEW_MIN = 0f
private const val PREVIEW_MAX = 30f

/** Smallest range shown, so a day with a single price still draws a valid arc. */
private const val MIN_RANGE = 1.0

/**
 * Ranged value complication showing the current electricity price, on a range from today's lowest to today's
 * highest price. Everything comes from the shared forecast of [ElectricityPriceForecastRepository], so it needs
 * no configuration and never hits the network itself.
 *
 * The price can only change when a quarter of an hour starts, which is when the complication is refreshed
 * (see [ElectricityPriceComplicationSchedule]). It is also refreshed whenever a new forecast is stored.
 */
@AndroidEntryPoint
class ElectricityPriceDataSourceService : SuspendingComplicationDataSourceService() {

    @Inject
    lateinit var forecastRepository: ElectricityPriceForecastRepository

    @Inject
    lateinit var clock: Clock

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.RANGED_VALUE) return null
        val now = Instant.ofEpochMilli(clock.now().toEpochMilliseconds())
        ElectricityPriceComplicationSchedule.scheduleNext(this, now)

        val price = forecastRepository.priceAt(now)
        val range = forecastRepository.todayPriceRange()
        val unit = forecastRepository.forecast.value?.unit.orEmpty()
        return if (price != null && range != null) {
            priceComplication(
                request,
                price,
                range,
                unit,
                validUntil = ElectricityPriceForecastSchedule.nextSlotStart(now),
            )
        } else {
            unknownComplication()
        }
    }

    private fun priceComplication(
        request: ComplicationRequest,
        price: Double,
        range: ClosedFloatingPointRange<Double>,
        unit: String,
        validUntil: Instant,
    ): ComplicationData {
        val min = range.start
        val max = maxOf(range.endInclusive, min + MIN_RANGE)
        val text = String.format(Locale.getDefault(), "%.2f", price)
        // Price level color for the icon and the arc, the chart opens on tap.
        val priceColor = ElectricityPriceTile.colorForPrice(price)
        return RangedValueComplicationData.Builder(
            value = price.coerceIn(min, max).toFloat(),
            min = min.toFloat(),
            max = max.toFloat(),
            contentDescription = PlainComplicationText.Builder(
                getString(R.string.complication_electricity_price_content_description, "$text $unit".trim()),
            ).build(),
        )
            .setText(PlainComplicationText.Builder(text).build())
            .setTapAction(ComplicationReceiver.getElectricityPriceIntent(this, request.complicationInstanceId))
            .setMonochromaticImage(icon(priceColor))
            // Single-color ramp so the whole arc reflects the current price level.
            // Only a hint: the watch face decides whether to use it.
            .setColorRamp(ColorRamp(intArrayOf(priceColor, priceColor), false))
            // The price is only valid for its quarter of an hour.
            .setValidTimeRange(TimeRange.before(validUntil))
            .build()
    }

    /** Shown while there is no forecast for the current quarter; refreshed once a forecast is stored. */
    private fun unknownComplication(): ComplicationData = RangedValueComplicationData.Builder(
        value = 0f,
        min = 0f,
        max = 1f,
        contentDescription = PlainComplicationText.Builder(getText(R.string.complication_electricity_price)).build(),
    )
        .setText(PlainComplicationText.Builder(getText(R.string.state_unknown)).build())
        .setMonochromaticImage(icon(Color.WHITE))
        .build()

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.RANGED_VALUE) return null
        return RangedValueComplicationData.Builder(
            value = PREVIEW_PRICE,
            min = PREVIEW_MIN,
            max = PREVIEW_MAX,
            contentDescription = PlainComplicationText.Builder(
                getText(R.string.complication_electricity_price),
            ).build(),
        )
            .setText(PlainComplicationText.Builder(PREVIEW_PRICE.toString()).build())
            .setMonochromaticImage(icon(Color.WHITE))
            .build()
    }

    private fun icon(color: Int): MonochromaticImage =
        MonochromaticImage.Builder(Icon.createWithBitmap(Mdi.Flash.toBitmap(this, ICON_SIZE_DP, color))).build()
}

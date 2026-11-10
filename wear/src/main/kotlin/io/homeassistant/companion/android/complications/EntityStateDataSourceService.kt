package io.homeassistant.companion.android.complications

import android.graphics.Color
import android.graphics.drawable.Icon
import androidx.annotation.StringRes
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dagger.hilt.android.AndroidEntryPoint
import io.github.timoptr.mdiicons.MdiIcon
import io.github.timoptr.mdiicons.toBitmap
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.data.integration.display.EntitiesForDisplayManager
import io.homeassistant.companion.android.common.data.integration.display.awaitLoadedOrNull
import io.homeassistant.companion.android.common.data.prefs.WearPrefsRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.wear.EntityStateComplications
import io.homeassistant.companion.android.database.wear.EntityStateComplicationsDao
import io.homeassistant.companion.android.tiles.ElectricityPriceTile
import io.homeassistant.companion.android.tiles.HourlyPrice
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.time.Clock
import timber.log.Timber

private const val COMPLICATION_ICON_SIZE_DP = 24

@AndroidEntryPoint
class EntityStateDataSourceService : SuspendingComplicationDataSourceService() {

    @Inject
    lateinit var serverManager: ServerManager

    @Inject
    lateinit var entityStateComplicationsDao: EntityStateComplicationsDao

    @Inject
    lateinit var entitiesForDisplayManager: EntitiesForDisplayManager

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var wearPrefsRepository: WearPrefsRepository

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT &&
            request.complicationType != ComplicationType.LONG_TEXT &&
            request.complicationType != ComplicationType.RANGED_VALUE
        ) {
            return null
        }

        val settings = entityStateComplicationsDao.get(request.complicationInstanceId)
            ?: return getErrorComplication(request, R.string.complication_entity_invalid, true)

        return if (request.complicationType == ComplicationType.RANGED_VALUE) {
            onPriceComplicationRequest(request, settings)
        } else {
            onTextComplicationRequest(request, settings)
        }
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        priceCache.remove(complicationInstanceId)
    }

    private suspend fun onTextComplicationRequest(
        request: ComplicationRequest,
        settings: EntityStateComplications,
    ): ComplicationData? {
        val entityId = settings.entityId
        val loadedState = entitiesForDisplayManager.snapshot(ServerManager.SERVER_ID_ACTIVE, listOf(entityId))
            .awaitLoadedOrNull() ?: return getErrorComplication(request, R.string.state_unknown)

        val displayEntity = loadedState.entity(entityId)
            ?: return getErrorComplication(request, R.string.complication_entity_invalid)

        val iconBitmap = displayEntity.icon.toBitmap(this, COMPLICATION_ICON_SIZE_DP, Color.WHITE)
        val title = titleFor(settings, displayEntity.name)
        val text = PlainComplicationText.Builder(
            displayEntity.state.resolve(this, withUnit = settings.showUnit),
        ).build()
        val monochromaticImage = MonochromaticImage.Builder(Icon.createWithBitmap(iconBitmap)).build()
        val tapAction = ComplicationReceiver.getComplicationToggleIntent(this, request.complicationInstanceId)

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = text,
                    contentDescription = contentDescription(),
                )
                    .setTitle(title)
                    .setTapAction(tapAction)
                    .setMonochromaticImage(monochromaticImage)
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = text,
                    contentDescription = contentDescription(),
                )
                    .setTitle(title)
                    .setTapAction(tapAction)
                    .setMonochromaticImage(monochromaticImage)
                    .build()
            }
            else -> null // Already handled by the caller
        }
    }

    /**
     * Ranged value complications are used for the current electricity price, which only changes every
     * quarter of an hour. The price is fetched once per quarter (see [ElectricityPriceComplicationSchedule])
     * and any other request in the same quarter (screen on, chart opened, periodic system update...) reuses
     * it without hitting the network, so it cannot replace a good value with an error.
     *
     * The value of a quarter is taken from the price forecast when available, because the entity state fetched
     * right after the quarter starts may still be the previous quarter's price. Without a forecast, an unchanged
     * state is fetched again a few times (see [ElectricityPriceComplicationSchedule.CONFIRM_DELAY]).
     *
     * When fetching fails, the previously fetched forecast is used, or the previous value is kept while it is
     * valid, and a retry is scheduled.
     */
    private suspend fun onPriceComplicationRequest(
        request: ComplicationRequest,
        settings: EntityStateComplications,
    ): ComplicationData {
        val schedule = ElectricityPriceComplicationSchedule
        val id = request.complicationInstanceId
        val now = Instant.ofEpochMilli(clock.now().toEpochMilliseconds())
        val slot = schedule.slotStart(now)
        val previousSlot = slot.minus(schedule.SLOT)
        val cached = priceCache[id]?.takeIf { it.settings == settings }

        if (cached != null && cached.fetchedSlot == slot && cached.confirmed) {
            buildPriceComplication(request, cached, slot)?.let {
                schedule.scheduleNext(this, now)
                return it
            }
        }

        // While confirming, compare with the same previous price as the first fetch of this quarter.
        val previousPrice = if (cached?.fetchedSlot == slot) cached.previousPrice else cached?.priceAt(previousSlot)
        val fresh = try {
            fetchPriceSource(settings, slot, now, previousPrice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Unable to fetch the electricity price for complication $id")
            null
        }
        if (fresh != null) {
            buildPriceComplication(request, fresh, slot)?.let {
                priceCache[id] = fresh
                if (fresh.confirmed) schedule.scheduleNext(this, now) else schedule.scheduleConfirm(this, now)
                return it
            }
        }

        // Fetching failed: use the forecast fetched earlier if it covers this quarter, otherwise keep the
        // previous quarter's value while it is valid.
        val forCurrentSlot = cached?.let { buildPriceComplication(request, it, slot) }
        val forPreviousSlot = cached
            ?.takeIf { forCurrentSlot == null && now.isBefore(schedule.validUntil(previousSlot)) }
            ?.let { buildPriceComplication(request, it, previousSlot) }
        val shownValidUntil = when {
            forCurrentSlot != null -> schedule.validUntil(slot)
            forPreviousSlot != null -> schedule.validUntil(previousSlot)
            else -> null
        }
        schedule.scheduleRetry(this, now, shownValidUntil)
        return forCurrentSlot ?: forPreviousSlot ?: getErrorComplication(request, R.string.state_unknown)
    }

    /**
     * Fetches the price entity (and its forecast) for the quarter starting at [slot].
     *
     * @param previousPrice the price shown for the previous quarter, to detect a state not updated yet
     * @return null if neither the forecast nor the entity state gives the price of this quarter
     */
    private suspend fun fetchPriceSource(
        settings: EntityStateComplications,
        slot: Instant,
        now: Instant,
        previousPrice: Double?,
    ): PriceSource? {
        val entityId = settings.entityId
        val displayEntity = entitiesForDisplayManager.snapshot(ServerManager.SERVER_ID_ACTIVE, listOf(entityId))
            .awaitLoadedOrNull()
            ?.entity(entityId)
            ?: return null
        // "unavailable", "unknown"... are not a price.
        val statePrice = displayEntity.rawState.toDoubleOrNull()

        // The range, unit and forecast come from the entity attributes, which are not part of the display entity
        val attributes = serverManager.integrationRepository().getEntity(entityId)?.attributes.orEmpty()
        val forecast = fetchForecast(entityId, attributes)
        val forecastPrice = ElectricityPriceComplicationSchedule.priceAt(forecast, slot)
        if (forecastPrice == null && statePrice == null) return null

        val confirmed = forecastPrice != null ||
            (previousPrice != null && statePrice != previousPrice) ||
            ElectricityPriceComplicationSchedule.isPastConfirmWindow(now)

        return PriceSource(
            settings = settings,
            fetchedSlot = slot,
            confirmed = confirmed,
            previousPrice = previousPrice,
            name = displayEntity.name,
            icon = displayEntity.icon,
            statePrice = statePrice,
            stateText = displayEntity.state.resolve(this, withUnit = settings.showUnit),
            stateDecimals = displayEntity.rawState.substringAfter('.', "").length
                .takeIf { '.' in displayEntity.rawState } ?: 2,
            unit = attributes["unit_of_measurement"] as? String ?: "",
            min = floor((attributes["min"] as? Number)?.toFloat() ?: 0f),
            max = max(30f, ceil((attributes["max"] as? Number)?.toFloat() ?: 30f)),
            forecast = forecast,
        )
    }

    /**
     * @return the price forecast from the price entity [attributes], or else from the forecast entity of the
     *   electricity price tile, empty if there is none.
     */
    private suspend fun fetchForecast(entityId: String, attributes: Map<String, Any?>): List<HourlyPrice> {
        ElectricityPriceTile.extractHourlyPrices(attributes).takeIf { it.isNotEmpty() }?.let { return it }
        val forecastEntityId = wearPrefsRepository.getElectricityPriceTileConfig().entityId
        if (forecastEntityId.isBlank() || forecastEntityId == entityId) return emptyList()
        return try {
            serverManager.integrationRepository().getEntity(forecastEntityId)
                ?.let { ElectricityPriceTile.extractHourlyPrices(it.attributes) }
                .orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Unable to fetch the electricity price forecast $forecastEntityId")
            emptyList()
        }
    }

    /** @return the price complication of [source] for the quarter starting at [slot], null if its price is unknown. */
    private fun buildPriceComplication(
        request: ComplicationRequest,
        source: PriceSource,
        slot: Instant,
    ): ComplicationData? {
        val price = source.priceAt(slot) ?: return null
        val text = if (price == source.statePrice) {
            source.stateText
        } else {
            String.format(Locale.getDefault(), "%.${source.stateDecimals}f", price) +
                if (source.settings.showUnit && source.unit.isNotBlank()) " ${source.unit}" else ""
        }

        // Price level color for the icon and the arc, the chart opens on tap.
        val priceColor = ElectricityPriceTile.colorForPrice(price)
        val iconBitmap = source.icon.toBitmap(this, COMPLICATION_ICON_SIZE_DP, priceColor)

        return RangedValueComplicationData.Builder(
            value = price.toFloat().coerceIn(source.min, source.max),
            min = source.min,
            max = source.max,
            contentDescription = contentDescription(),
        )
            .setTitle(titleFor(source.settings, source.name))
            .setText(PlainComplicationText.Builder(text).build())
            .setTapAction(ComplicationReceiver.getElectricityPriceIntent(this, request.complicationInstanceId))
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithBitmap(iconBitmap)).build())
            // Single-color ramp so the whole arc reflects the current price level.
            // Only a hint: the watch face decides whether to use it.
            .setColorRamp(ColorRamp(intArrayOf(priceColor, priceColor), false))
            // The price is only valid for its quarter, plus some time to fetch the next one.
            .setValidTimeRange(TimeRange.before(ElectricityPriceComplicationSchedule.validUntil(slot)))
            .build()
    }

    private fun titleFor(settings: EntityStateComplications, name: String): PlainComplicationText? =
        if (settings.showTitle) PlainComplicationText.Builder(name).build() else null

    private fun contentDescription(): PlainComplicationText =
        PlainComplicationText.Builder(getText(R.string.complication_entity_state_content_description)).build()

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val text = PlainComplicationText.Builder(getText(R.string.complication_entity_state_preview)).build()
        val contentDescription = PlainComplicationText.Builder(
            getText(R.string.complication_entity_state_content_description),
        ).build()
        val title = PlainComplicationText.Builder(getText(R.string.entity)).build()
        val monochromaticImage = MonochromaticImage.Builder(
            Icon.createWithResource(
                this,
                io.homeassistant.companion.android.R.drawable.ic_lightbulb,
            ),
        ).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = text,
                    contentDescription = contentDescription,
                )
                    .setTitle(title)
                    .setMonochromaticImage(monochromaticImage)
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = text,
                    contentDescription = contentDescription,
                )
                    .setTitle(title)
                    .setMonochromaticImage(monochromaticImage)
                    .build()
            }
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = 4.28f,
                    min = 0f,
                    max = 30f,
                    contentDescription = contentDescription,
                )
                    .setTitle(title)
                    .setText(PlainComplicationText.Builder("4.28").build())
                    .setMonochromaticImage(monochromaticImage)
                    .build()
            }
            else -> {
                Timber.w("Preview for unsupported complication type $type requested")
                null
            }
        }
    }

    /**
     * Get a simple complication for errors with [textRes] in the text slot.
     *
     * @param setTapAction If tapping the complication should open configuration
     */
    private fun getErrorComplication(
        request: ComplicationRequest,
        @StringRes textRes: Int,
        setTapAction: Boolean = false,
    ): ComplicationData {
        val text = PlainComplicationText.Builder(
            if (setTapAction) {
                "+"
            } else {
                getText(textRes)
            },
        ).build()
        val contentDescription = PlainComplicationText.Builder(
            getText(R.string.complication_entity_state_content_description),
        ).build()
        val tapAction = if (setTapAction) {
            ComplicationReceiver.getComplicationConfigureIntent(this, request.complicationInstanceId)
        } else {
            null
        }
        // The returned type must match the requested one, or the system crashes the app.
        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = text,
                contentDescription = contentDescription,
            ).setTapAction(tapAction).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 0f,
                min = 0f,
                max = 1f,
                contentDescription = contentDescription,
            ).setText(text).setTapAction(tapAction).build()
            else -> LongTextComplicationData.Builder(
                text = text,
                contentDescription = contentDescription,
            ).setTapAction(tapAction).build()
        }
    }

    /** Last successful fetch of a price complication, with the settings and the quarter it was fetched for. */
    private data class PriceSource(
        val settings: EntityStateComplications,
        val fetchedSlot: Instant,
        /** Whether the price of [fetchedSlot] is known to be up to date (see [fetchPriceSource]). */
        val confirmed: Boolean,
        /** The previous quarter's price, which an unconfirmed state is compared with. */
        val previousPrice: Double?,
        val name: String,
        val icon: MdiIcon,
        val statePrice: Double?,
        val stateText: String,
        val stateDecimals: Int,
        val unit: String,
        val min: Float,
        val max: Float,
        val forecast: List<HourlyPrice>,
    ) {
        /** @return the price of the quarter starting at [slot] from the forecast, or else from the state. */
        fun priceAt(slot: Instant): Double? = ElectricityPriceComplicationSchedule.priceAt(forecast, slot)
            ?: statePrice.takeIf { slot == fetchedSlot }
    }

    private companion object {
        /** Per complication instance id, shared by service instances and lost (then re-fetched) with the process. */
        val priceCache = ConcurrentHashMap<Int, PriceSource>()
    }
}

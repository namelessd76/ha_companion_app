package io.homeassistant.companion.android.tiles

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.common.data.prefs.WearPrefsRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.complications.ElectricityPriceDataSourceService
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Single source of the electricity price forecast for every consumer (the [ElectricityPriceTile], the
 * [ElectricityPriceActivity] chart and the price complication), kept in memory only.
 *
 * The forecast entity configured for the tile is retrieved from the server when the application starts
 * ([start]), every day at [ElectricityPriceForecastSchedule.DAILY_RETRIEVAL_TIME] and on a manual refresh
 * ([refresh]). Each retrieval is retried every [ElectricityPriceForecastSchedule.RETRY_DELAY] until the
 * forecast is valid (see [ElectricityPriceForecastSchedule.isValid]). Consumers never fetch the forecast
 * themselves; they are asked to update whenever a new forecast is stored. The stored forecast always belongs to
 * the configured forecast entity, it is dropped when that entity changes.
 *
 * It also serves the prices derived from the forecast: the current price ([currentPrice]) and today's price
 * range ([todayPriceRange]).
 *
 * The daily retrieval and the retries are driven by [ElectricityPriceForecastAlarms].
 */
@Singleton
class ElectricityPriceForecastRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverManager: ServerManager,
    private val wearPrefsRepository: WearPrefsRepository,
    private val clock: Clock,
) {
    // Lives as long as the process, like the forecast it retrieves; there is no injectable dispatcher yet.
    @Suppress("InjectDispatcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retrievalMutex = Mutex()
    private val forecastState = MutableStateFlow<ElectricityPriceForecast?>(null)

    @Volatile
    private var lastValidRetrieval: Instant? = null

    /** The latest retrieved forecast, null until a retrieval succeeded. */
    val forecast: StateFlow<ElectricityPriceForecast?> = forecastState.asStateFlow()

    /** Retrieves the forecast now and schedules the daily retrieval. Called once when the application starts. */
    fun start() {
        ElectricityPriceForecastAlarms.scheduleDailyRetrieval(context, now())
        scope.launch { retrieve() }
    }

    /**
     * Forces a retrieval now (retried until valid), without changing the daily retrieval time.
     *
     * @return the job of the first attempt
     */
    fun refresh(): Job = scope.launch { retrieve() }

    /** @return the price of the current quarter of an hour, null when the forecast does not cover it. */
    fun currentPrice(): Double? = priceAt(now())

    /** @return the price of the quarter of an hour [at] belongs to, null when the forecast does not cover it. */
    fun priceAt(at: Instant): Double? = forecastState.value?.priceAt(at)

    /** @return the lowest and highest prices of today (local time), null when the forecast has none. */
    fun todayPriceRange(): ClosedFloatingPointRange<Double>? {
        val zone = ZoneId.systemDefault()
        return forecastState.value?.priceRangeOn(now().atZone(zone).toLocalDate(), zone)
    }

    /**
     * Handles an alarm [action] fired for [ElectricityPriceForecastReceiver].
     *
     * @return the job of the retrieval attempt
     */
    internal fun onAlarm(action: String?): Job = scope.launch {
        when (action) {
            ElectricityPriceForecastAlarms.ACTION_DAILY_RETRIEVAL -> {
                ElectricityPriceForecastAlarms.scheduleDailyRetrieval(context, now())
                // The alarm may have started the process, which already retrieved the forecast in [start].
                val last = lastValidRetrieval
                if (last == null || last.isBefore(now().minus(ElectricityPriceForecastSchedule.RETRY_DELAY))) {
                    retrieve()
                }
            }
            ElectricityPriceForecastAlarms.ACTION_RETRY -> {
                // A manual refresh may already have retrieved a valid forecast.
                val prices = forecastState.value?.prices.orEmpty()
                if (!ElectricityPriceForecastSchedule.isValid(prices, now())) retrieve()
            }
        }
    }

    /** One retrieval attempt: stores what was received and schedules a retry unless the forecast is valid. */
    private suspend fun retrieve() = retrievalMutex.withLock {
        val entityId = wearPrefsRepository.getElectricityPriceTileConfig().entityId
        if (forecastState.value?.let { it.entityId != entityId } == true) {
            // The forecast entity changed: the stored forecast is not the configured one anymore.
            forecastState.value = null
            notifyConsumers()
        }
        if (!serverManager.isRegistered() || entityId.isBlank()) {
            // Nothing to retrieve until the next trigger (application start, daily retrieval or manual refresh).
            ElectricityPriceForecastAlarms.cancelRetry(context)
            return@withLock
        }
        val now = now()
        val received = fetch(entityId, now)
        if (received != null) store(received)
        if (received != null && ElectricityPriceForecastSchedule.isValid(received.prices, now)) {
            Timber.d("Electricity price forecast of $entityId retrieved")
            lastValidRetrieval = now
            ElectricityPriceForecastAlarms.cancelRetry(context)
        } else {
            Timber.w("Electricity price forecast of $entityId is not valid yet, retrying")
            ElectricityPriceForecastAlarms.scheduleRetry(context, now)
        }
    }

    /** @return the forecast of [entityId] from the server, null on error or when it holds no price. */
    private suspend fun fetch(entityId: String, now: Instant): ElectricityPriceForecast? = try {
        withTimeoutOrNull(FETCH_TIMEOUT) {
            serverManager.integrationRepository().getEntity(entityId)
        }?.let { entity ->
            ElectricityPriceForecast(
                entityId = entityId,
                prices = ElectricityPriceTile.extractHourlyPrices(entity.attributes),
                unit = (entity.attributes["unit_of_measurement"] as? String).orEmpty(),
                retrievedAt = now,
            )
        }?.takeIf { it.prices.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Unable to retrieve the electricity price forecast $entityId")
        null
    }

    /**
     * Stores [received] unless the current forecast of the same entity reaches further (a partial answer must not
     * replace a complete one), then asks the consumers to update.
     */
    private fun store(received: ElectricityPriceForecast) {
        val current = forecastState.value
        val keepCurrent = current != null &&
            current.entityId == received.entityId &&
            (ElectricityPriceForecastSchedule.coverageEnd(current.prices) ?: Instant.MIN)
                .isAfter(ElectricityPriceForecastSchedule.coverageEnd(received.prices) ?: Instant.MIN)
        if (keepCurrent) return
        forecastState.value = received
        val changed = current?.entityId != received.entityId ||
            current.prices != received.prices ||
            current.unit != received.unit
        if (changed) notifyConsumers()
    }

    private fun notifyConsumers() {
        try {
            ElectricityPriceTile.requestUpdate(context)
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, ElectricityPriceDataSourceService::class.java))
                .requestUpdateAll()
        } catch (e: Exception) {
            Timber.w(e, "Unable to request an update of the electricity price consumers")
        }
    }

    private fun now(): Instant = Instant.ofEpochMilli(clock.now().toEpochMilliseconds())

    private companion object {
        /** Keeps an attempt well within the broadcast receiver time budget. */
        val FETCH_TIMEOUT = 20.seconds
    }
}

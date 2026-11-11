package io.homeassistant.companion.android.tiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.util.launchAsync
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull

/** Runs the daily retrieval and the retries of the electricity price forecast when their alarm fires. */
@AndroidEntryPoint
class ElectricityPriceForecastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var forecastRepository: ElectricityPriceForecastRepository

    // Same threading as the other receivers; there is no injectable dispatcher in this module yet.
    @Suppress("InjectDispatcher")
    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val attempt = forecastRepository.onAlarm(intent.action)
        // Keep the process alive while the attempt runs; it keeps running in the repository scope past the wait.
        launchAsync(receiverScope) { withTimeoutOrNull(MAX_WAIT) { attempt.join() } }
    }

    private companion object {
        /** Below the broadcast receiver time budget of [launchAsync]. */
        val MAX_WAIT = 25.seconds
    }
}

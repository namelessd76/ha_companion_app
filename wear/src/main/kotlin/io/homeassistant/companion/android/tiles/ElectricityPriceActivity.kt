package io.homeassistant.companion.android.tiles

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.prefs.WearPrefsRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.complications.EntityStateDataSourceService
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Full-screen version of [ElectricityPriceTile], opened by tapping a ranged value entity complication
 * (Wear OS offers no way to jump to a specific tile). Tap anywhere to refresh.
 */
@AndroidEntryPoint
class ElectricityPriceActivity : ComponentActivity() {

    companion object {
        fun newInstance(context: Context): Intent = Intent(context, ElectricityPriceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    @Inject
    lateinit var serverManager: ServerManager

    @Inject
    lateinit var wearPrefsRepository: WearPrefsRepository

    private lateinit var chartView: ImageView
    private lateinit var messageView: TextView
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        chartView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        messageView = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(chartView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(messageView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setOnClickListener { loadChart() }
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        loadChart()
        // Tapping the complication used to refresh it, keep it up to date when opening the chart
        // (the price complication only fetches again when a new quarter of an hour has started).
        ComplicationDataSourceUpdateRequester
            .create(this, ComponentName(this, EntityStateDataSourceService::class.java))
            .requestUpdateAll()
    }

    private fun loadChart() {
        if (loadJob?.isActive == true) return
        loadJob = lifecycleScope.launch {
            val config = wearPrefsRepository.getElectricityPriceTileConfig()
            val message = when {
                !serverManager.isRegistered() -> getString(commonR.string.electricity_price_tile_log_in)
                config.entityId.isBlank() -> getString(commonR.string.electricity_price_tile_no_entity_yet)
                else -> null
            }
            if (message != null) {
                showMessage(message)
                return@launch
            }

            try {
                val bitmap = ElectricityPriceTile.buildChartBitmap(
                    this@ElectricityPriceActivity,
                    serverManager,
                    config,
                    currentGeometry(),
                )
                chartView.setImageBitmap(bitmap)
                messageView.text = null
            } catch (e: Exception) {
                Timber.e(e, "Unable to build electricity price chart")
                showMessage(getString(commonR.string.electricity_price_tile_no_data))
            }
        }
    }

    private fun showMessage(message: String) {
        chartView.setImageBitmap(null)
        messageView.text = message
    }

    private fun currentGeometry(): ChartGeometry {
        val metrics = resources.displayMetrics
        return ChartGeometry(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            density = metrics.density,
            fontScale = resources.configuration.fontScale,
            isRound = resources.configuration.isScreenRound,
        )
    }
}

private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT

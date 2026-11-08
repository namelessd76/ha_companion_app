package io.homeassistant.companion.android.tiles.diablo

import android.content.Context
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.TimeInterval
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.R
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.prefs.WearPrefsRepository
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.guava.future

private const val RESOURCES_VERSION = "1"

/** Number of one-minute timeline entries sent per tile request; the renderer swaps them locally. */
private const val TIMELINE_ENTRIES = 30
private val ENTRY_DURATION: Duration = Duration.ofMinutes(1)

private const val MINUTES_PER_HOUR = 60L

/**
 * Counts down to the next Diablo IV world boss spawn, which happens every
 * [DiabloWorldBossSchedule.SPAWN_INTERVAL], based on a confirmed past spawn set in the tile settings.
 * See [DiabloWorldBossLayout] for the look.
 *
 * A single tile request returns [TIMELINE_ENTRIES] one-minute entries; the system shows each at its time
 * without waking the app, so the countdown only costs something while the tile is actually displayed.
 * The spawn math is done on absolute instants, so the countdown stays right across time zone changes.
 * Tapping the tile opens its settings. Doesn't need a Home Assistant server.
 */
@AndroidEntryPoint
class DiabloWorldBossTile : TileService() {

    companion object {
        /** Asks the system to refresh the tile, e.g. after its configuration changed. */
        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(DiabloWorldBossTile::class.java)
        }

        /** @return [remaining] formatted as `H:MM`, rounding partial minutes up. */
        internal fun formatRemaining(remaining: Duration): String {
            val totalMinutes = ceilMinutes(remaining)
            return String.format(
                Locale.ROOT,
                "%d:%02d",
                totalMinutes / MINUTES_PER_HOUR,
                totalMinutes % MINUTES_PER_HOUR,
            )
        }

        private fun ceilMinutes(duration: Duration): Long {
            val minutes = duration.toMinutes()
            return if (duration > Duration.ofMinutes(minutes)) minutes + 1 else minutes
        }
    }

    private val serviceJob = Job()

    // Same threading as the other tiles; there is no injectable dispatcher in this module yet.
    @Suppress("InjectDispatcher")
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Inject
    lateinit var wearPrefsRepository: WearPrefsRepository

    @Inject
    lateinit var clock: Clock

    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<Tile> = serviceScope.future {
        val reference = wearPrefsRepository.getDiabloWorldBossTileConfig().referenceSpawn
        val layout = DiabloWorldBossLayout(packageName, requestParams.deviceConfiguration)
        val builder = Tile.Builder().setResourcesVersion(RESOURCES_VERSION)
        if (reference == null) {
            val setupText = getString(commonR.string.diablo_world_boss_tile_no_reference)
            builder.setTileTimeline(Timeline.fromLayoutElement(layout.setup(setupText)))
        } else {
            builder
                .setTileTimeline(countdownTimeline(reference, now(), layout))
                // Ask for new entries shortly before the current batch runs out.
                .setFreshnessIntervalMillis(ENTRY_DURATION.multipliedBy(TIMELINE_ENTRIES - 1L).toMillis())
        }
        builder.build()
    }

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<Resources> =
        serviceScope.future {
            Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(
                    DiabloWorldBossLayout.RESOURCE_LOGO,
                    ImageResource.Builder()
                        .setAndroidResourceByResId(
                            AndroidImageResourceByResId.Builder().setResourceId(R.drawable.diablo_iv_logo).build(),
                        )
                        .build(),
                )
                .build()
        }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun now(): Instant = Instant.ofEpochMilli(clock.now().toEpochMilliseconds())

    /**
     * One entry per minute, each valid for the minute in which the rounded-up remaining time is constant.
     * Entry boundaries are aligned on the spawn instant, so the display reaches the next cycle exactly when
     * the boss spawns.
     */
    private fun countdownTimeline(reference: Instant, now: Instant, layout: DiabloWorldBossLayout): Timeline {
        val nextSpawn = DiabloWorldBossSchedule.nextSpawn(reference, now)
        val firstEntryMinutes = ceilMinutes(Duration.between(now, nextSpawn))
        val firstEntryStart = nextSpawn.minus(ENTRY_DURATION.multipliedBy(firstEntryMinutes))
        val timeline = Timeline.Builder()
        repeat(TIMELINE_ENTRIES) { index ->
            val start = firstEntryStart.plus(ENTRY_DURATION.multipliedBy(index.toLong()))
            val end = start.plus(ENTRY_DURATION)
            // Measured just before the entry's end, so it's the rounded-up remaining time during the entry.
            val remaining = DiabloWorldBossSchedule.timeUntilNextSpawn(reference, end.minusMillis(1))
            val remainingRatio = remaining.toMillis().toFloat() / DiabloWorldBossSchedule.SPAWN_INTERVAL.toMillis()
            timeline.addTimelineEntry(
                TimelineEntry.Builder()
                    .setValidity(
                        TimeInterval.Builder()
                            .setStartMillis(start.toEpochMilli())
                            .setEndMillis(end.toEpochMilli())
                            .build(),
                    )
                    .setLayout(
                        Layout.Builder()
                            .setRoot(layout.countdown(formatRemaining(remaining), remainingRatio))
                            .build(),
                    )
                    .build(),
            )
        }
        return timeline.build()
    }
}

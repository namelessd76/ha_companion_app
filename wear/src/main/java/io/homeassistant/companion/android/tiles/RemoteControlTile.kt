package io.homeassistant.companion.android.tiles

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.InlineImageResource
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.colorInt
import com.mikepenz.iconics.utils.sizeDp
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.prefs.WearPrefsRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.util.getIcon
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.guava.future

// Controlled entities
private const val MP_ENTITY1 = "remote.z3000_pro"
private const val MP_ENTITY2 = "media_player.home_theater_room"
private const val MP_ENTITY3 = "media_player.tv"
private const val SW_HOMETHEATER = "input_boolean.sw_hometheater"

// Colors (match the mockup: light cards, HA-blue icons)
private const val COLOR_BACKGROUND = 0x00000000.toInt()
private const val COLOR_PANEL = 0x00000000.toInt()
private const val COLOR_CARD = 0xFFFFFFFF.toInt()
private const val COLOR_CARD_BORDER = 0xFFE0E0E0.toInt()
private const val COLOR_ICON = 0xFF44739E.toInt()
private const val COLOR_ICON_ON = 0xFF2DB84C.toInt()
private const val COLOR_LABEL = 0xFF727272.toInt()

// Sizes relative to the screen diameter, measured from the mockup
private const val CARD_RATIO = 0.218f
private const val GAP_RATIO = 0.0117f
private const val PANEL_PADDING_RATIO = 0.033f
private const val CORNER_RATIO = 0.08f // relative to card size
private const val ICON_RATIO = 0.40f // relative to card size
private const val ICON_WITH_LABEL_RATIO = 0.34f // relative to card size
private const val LABEL_TEXT_SIZE = 8f // sp
private const val BORDER_WIDTH = 1f // dp

private const val RESOURCES_VERSION = "1"
private const val RESOURCE_SWITCH_ON = "rc_switch_on"
private const val RESOURCE_SWITCH_OFF = "rc_switch_off"

/** A single button on the remote: what it shows and which Home Assistant action it calls. */
private data class RcButton(
    val id: String,
    val icon: String,
    val entityId: String,
    val domain: String,
    val action: String,
    val command: String? = null
)

/** Buttons in display order: left to right, top to bottom, 3 per row. */
private val BUTTONS = listOf(
    RcButton("rc_back", "mdi:restore", MP_ENTITY1, "remote", "send_command", "Key.Back"),
    RcButton("rc_up", "mdi:arrow-up-drop-circle", MP_ENTITY1, "remote", "send_command", "Key.Up"),
    RcButton("rc_menu", "mdi:menu", MP_ENTITY1, "remote", "send_command", "Key.Menu"),
    RcButton("rc_left", "mdi:arrow-left-drop-circle", MP_ENTITY1, "remote", "send_command", "Key.Left"),
    RcButton("rc_ok", "mdi:circle-slice-8", MP_ENTITY1, "remote", "send_command", "Key.Ok"),
    RcButton("rc_right", "mdi:arrow-right-drop-circle", MP_ENTITY1, "remote", "send_command", "Key.Right"),
    RcButton("rc_vol_up", "mdi:volume-plus", MP_ENTITY2, "media_player", "volume_up"),
    RcButton("rc_down", "mdi:arrow-down-drop-circle", MP_ENTITY1, "remote", "send_command", "Key.Down"),
    RcButton("rc_vol_down", "mdi:volume-minus", MP_ENTITY2, "media_player", "volume_down"),
    RcButton("rc_pause", "mdi:sleep-off", MP_ENTITY3, "media_player", "media_pause"),
    RcButton("rc_clear", "mdi:monitor-off", MP_ENTITY3, "media_player", "clear_playlist"),
    RcButton("rc_switch", "mdi:television-classic", SW_HOMETHEATER, "input_boolean", "toggle")
)
private const val COLUMNS = 3

/**
 * Home theater remote control: a 3x4 grid of buttons. All buttons but the last call a fixed Home
 * Assistant action; the last one toggles [SW_HOMETHEATER] and its icon color reflects that entity's
 * state. The state is only read when the tile is requested (shown or a button pressed).
 */
@AndroidEntryPoint
class RemoteControlTile : TileService() {

    companion object {
        private const val TAG = "RemoteControlTile"

        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(RemoteControlTile::class.java)
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
            val registered = serverManager.isRegistered()
            if (registered) {
                BUTTONS.firstOrNull { it.id == requestParams.currentState.lastClickableId }?.let { button ->
                    if (wearPrefsRepository.getWearHapticFeedback()) hapticClick(applicationContext)
                    callButtonAction(button)
                }
            }

            Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(
                    if (registered) {
                        Timeline.fromLayoutElement(layout(requestParams.deviceConfiguration, isSwitchOn()))
                    } else {
                        loggedOutTimeline(
                            this@RemoteControlTile,
                            requestParams,
                            commonR.string.remote_control_tile,
                            commonR.string.remote_control_tile_log_in
                        )
                    }
                ).build()
        }

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<Resources> =
        serviceScope.future {
            val device = requestParams.deviceConfiguration
            val cardSize = cardSize(device)
            Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .apply {
                    BUTTONS.filter { it.entityId != SW_HOMETHEATER }.forEach { button ->
                        addIdToImageMapping(button.id, iconResource(button.icon, COLOR_ICON, cardSize * ICON_RATIO, device))
                    }
                    val switchIcon = BUTTONS.last().icon
                    val switchIconSize = cardSize * ICON_WITH_LABEL_RATIO
                    addIdToImageMapping(RESOURCE_SWITCH_ON, iconResource(switchIcon, COLOR_ICON_ON, switchIconSize, device))
                    addIdToImageMapping(RESOURCE_SWITCH_OFF, iconResource(switchIcon, COLOR_ICON, switchIconSize, device))
                }
                .build()
        }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private suspend fun callButtonAction(button: RcButton) {
        val actionData = hashMapOf<String, Any>("entity_id" to button.entityId)
        button.command?.let { actionData["command"] = it }
        try {
            serverManager.integrationRepository().callAction(button.domain, button.action, actionData)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to call ${button.domain}.${button.action} for ${button.entityId}", e)
        }
    }

    private suspend fun isSwitchOn(): Boolean = try {
        serverManager.integrationRepository().getEntity(SW_HOMETHEATER)?.state == "on"
    } catch (e: Exception) {
        Log.e(TAG, "Unable to get state of $SW_HOMETHEATER", e)
        false
    }

    private fun diameter(device: DeviceParameters): Float =
        min(device.screenWidthDp, device.screenHeightDp).toFloat()

    private fun cardSize(device: DeviceParameters): Float = diameter(device) * CARD_RATIO

    private fun iconResource(icon: String, color: Int, sizeDp: Float, device: DeviceParameters): ImageResource {
        val sizePx = (sizeDp * device.screenDensity).roundToInt()
        val bitmap = IconicsDrawable(this, getIcon(icon, "input_boolean", this)).apply {
            colorInt = color
            this.sizeDp = sizeDp.roundToInt()
        }.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        // PNG keeps transparency and is supported by all tile renderers
        val data = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        return ImageResource.Builder()
            .setInlineResource(
                InlineImageResource.Builder()
                    .setData(data)
                    .setWidthPx(sizePx)
                    .setHeightPx(sizePx)
                    .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                    .build()
            )
            .build()
    }

    private fun layout(device: DeviceParameters, switchOn: Boolean): LayoutElement {
        val diameter = diameter(device)
        val cardSize = cardSize(device)
        val gap = diameter * GAP_RATIO

        val grid = Column.Builder()
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(COLOR_PANEL)).build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(diameter * PANEL_PADDING_RATIO))
                            .setEnd(dp(diameter * PANEL_PADDING_RATIO))
                            .build()
                    )
                    .build()
            )
        BUTTONS.chunked(COLUMNS).forEachIndexed { rowIndex, rowButtons ->
            if (rowIndex > 0) grid.addContent(Spacer.Builder().setHeight(dp(gap)).build())
            val row = Row.Builder().setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            rowButtons.forEachIndexed { columnIndex, button ->
                if (columnIndex > 0) row.addContent(Spacer.Builder().setWidth(dp(gap)).build())
                row.addContent(buttonLayout(button, cardSize, switchOn))
            }
            grid.addContent(row.build())
        }

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder().setColor(argb(COLOR_BACKGROUND)).build()
                    )
                    .build()
            )
            .addContent(grid.build())
            .build()
    }

    private fun buttonLayout(button: RcButton, cardSize: Float, switchOn: Boolean): LayoutElement {
        val isSwitch = button.entityId == SW_HOMETHEATER
        val content: LayoutElement = if (isSwitch) {
            val iconSize = cardSize * ICON_WITH_LABEL_RATIO
            Column.Builder()
                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                .addContent(
                    LayoutElementBuilders.Image.Builder()
                        .setResourceId(if (switchOn) RESOURCE_SWITCH_ON else RESOURCE_SWITCH_OFF)
                        .setWidth(dp(iconSize))
                        .setHeight(dp(iconSize))
                        .build()
                )
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText(getString(if (switchOn) commonR.string.state_on else commonR.string.state_off))
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(sp(LABEL_TEXT_SIZE))
                                .setColor(argb(COLOR_LABEL))
                                .build()
                        )
                        .build()
                )
                .build()
        } else {
            val iconSize = cardSize * ICON_RATIO
            LayoutElementBuilders.Image.Builder()
                .setResourceId(button.id)
                .setWidth(dp(iconSize))
                .setHeight(dp(iconSize))
                .build()
        }

        return Box.Builder()
            .setWidth(dp(cardSize))
            .setHeight(dp(cardSize))
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(COLOR_CARD))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder().setRadius(dp(cardSize * CORNER_RATIO)).build()
                            )
                            .build()
                    )
                    .setBorder(
                        ModifiersBuilders.Border.Builder()
                            .setWidth(dp(BORDER_WIDTH))
                            .setColor(argb(COLOR_CARD_BORDER))
                            .build()
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(button.id)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build()
                    )
                    .build()
            )
            .addContent(content)
            .build()
    }
}

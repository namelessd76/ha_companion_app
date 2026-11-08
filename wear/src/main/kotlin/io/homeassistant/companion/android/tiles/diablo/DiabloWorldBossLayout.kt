package io.homeassistant.companion.android.tiles.diablo

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.DegreesProp
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders.ARC_ANCHOR_START
import androidx.wear.protolayout.LayoutElementBuilders.Arc
import androidx.wear.protolayout.LayoutElementBuilders.ArcLine
import androidx.wear.protolayout.LayoutElementBuilders.ArcSpacer
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.CONTENT_SCALE_MODE_FIT
import androidx.wear.protolayout.LayoutElementBuilders.FONT_WEIGHT_BOLD
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.STROKE_CAP_BUTT
import androidx.wear.protolayout.LayoutElementBuilders.StrokeCapProp
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import io.homeassistant.companion.android.home.HomeActivity
import kotlin.math.ceil

private const val FULL_CIRCLE_DEGREES = 360f

/** Each dash of the outer ring stands for this many minutes of the cycle. */
private const val MINUTES_PER_DASH = 5L
private val DASH_COUNT = (DiabloWorldBossSchedule.SPAWN_INTERVAL.toMinutes() / MINUTES_PER_DASH).toInt()

/** Gap length, relative to a dash + gap step; close to half keeps the dashes clearly separated. */
private const val DASH_GAP_RATIO = 0.45f

private const val DASH_THICKNESS_DP = 8f
private const val RING_GAP_DP = 3f
private const val PROGRESS_THICKNESS_DP = 9f
private const val LOGO_INSET_DP = DASH_THICKNESS_DP + RING_GAP_DP + PROGRESS_THICKNESS_DP + RING_GAP_DP

/** Absorbs float rounding so e.g. exactly 10 minutes left lights 2 dashes, not 3. */
private const val RATIO_TOLERANCE = 1e-4f
private const val COUNTDOWN_TEXT_SIZE_SP = 22f
private const val SETUP_TEXT_SIZE_SP = 12f
private const val TEXT_MAX_LINES = 3

/**
 * Vertical position of the text center, below the logo center, relative to the logo diameter.
 * Lands it in the dark band under the "DIABLO IV" lettering of the background logo.
 */
private const val TEXT_OFFSET_RATIO = 0.18f

private const val COLOR_DASH_ON = 0xFF3FE0F2.toInt() // cyan
private const val COLOR_DASH_OFF = 0xFF16363D.toInt() // dimmed cyan
private const val COLOR_PROGRESS = 0xFFFFFFFF.toInt() // white
private const val COLOR_PROGRESS_OFF = 0xFF3A3A3A.toInt() // dimmed white
private const val COLOR_TEXT = 0xFFFFFFFF.toInt() // white

/**
 * Builds the Diablo IV world boss tile layouts: the Diablo IV logo as background, surrounded by an outer
 * ring of cyan dashes and a thick white arc that both shrink as the spawn gets closer, and a text below
 * the logo's lettering. Tapping anywhere opens the tile settings.
 *
 * @param packageName the app package, used to launch the settings on tap
 * @param device the screen the layout is built for
 */
internal class DiabloWorldBossLayout(private val packageName: String, private val device: DeviceParameters) {

    companion object {
        /** Resource id of the background logo image. */
        const val RESOURCE_LOGO = "diablo_iv_logo"
    }

    /**
     * @param countdownText the remaining time, already formatted
     * @param remainingRatio the remaining part of the spawn cycle, in `(0, 1]`
     */
    fun countdown(countdownText: String, remainingRatio: Float): LayoutElement = rootBox()
        .addContent(dashRing(remainingRatio))
        .addContent(insetBox(DASH_THICKNESS_DP + RING_GAP_DP).addContent(progressArc(remainingRatio)).build())
        .addContent(logo())
        .addContent(belowLogoText(countdownText, COUNTDOWN_TEXT_SIZE_SP, bold = true))
        .build()

    /** The logo alone with [message] below its lettering, shown until a reference spawn is set. */
    fun setup(message: String): LayoutElement = rootBox()
        .addContent(logo())
        .addContent(belowLogoText(message, SETUP_TEXT_SIZE_SP, bold = false))
        .build()

    private fun rootBox(): Box.Builder = Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setModifiers(
            Modifiers.Builder()
                .setClickable(
                    Clickable.Builder()
                        .setOnClick(
                            HomeActivity.getLaunchAction(
                                packageName,
                                tileId = 0,
                                HomeActivity.Companion.LaunchMode.DiabloWorldBossTile,
                            ),
                        )
                        .build(),
                )
                .build(),
        )

    private fun insetBox(insetDp: Float): Box.Builder = Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setModifiers(Modifiers.Builder().setPadding(Padding.Builder().setAll(dp(insetDp)).build()).build())

    private fun logo(): LayoutElement = insetBox(LOGO_INSET_DP)
        .addContent(
            Image.Builder()
                .setResourceId(RESOURCE_LOGO)
                .setWidth(expand())
                .setHeight(expand())
                .setContentScaleMode(CONTENT_SCALE_MODE_FIT)
                .build(),
        )
        .build()

    /**
     * [value] centered horizontally and pushed below the logo center by [TEXT_OFFSET_RATIO] of the logo
     * diameter: padding the top of a full-size box by twice the offset moves the center of its remaining
     * area down by exactly the offset.
     */
    private fun belowLogoText(value: String, sizeSp: Float, bold: Boolean): LayoutElement {
        val logoDiameterDp = minOf(device.screenWidthDp, device.screenHeightDp) - 2 * LOGO_INSET_DP
        val offsetDp = logoDiameterDp * TEXT_OFFSET_RATIO
        val padding = Padding.Builder()
            .setTop(dp(2 * offsetDp))
            .setStart(dp(2 * LOGO_INSET_DP))
            .setEnd(dp(2 * LOGO_INSET_DP))
            .build()
        val text = Text.Builder()
            .setText(value)
            .setMaxLines(TEXT_MAX_LINES)
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(sizeSp))
                    .setColor(argb(COLOR_TEXT))
                    .apply { if (bold) setWeight(FONT_WEIGHT_BOLD) }
                    .build(),
            )
            .build()
        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(Modifiers.Builder().setPadding(padding).build())
            .addContent(text)
            .build()
    }

    /**
     * The outer ring of [DASH_COUNT] dashes, one per [MINUTES_PER_DASH] minutes of the cycle, starting at
     * 12 o'clock and going clockwise. The dashes of the remaining time (the last ones, next to 12 o'clock)
     * are lit and the elapsed ones dimmed, so the ring matches the white [progressArc]. Dashes have flat ends: round caps would
     * grow each dash by its thickness and eat the gaps.
     */
    private fun dashRing(remainingRatio: Float): LayoutElement {
        val stepDegrees = FULL_CIRCLE_DEGREES / DASH_COUNT
        val halfGap = ArcSpacer.Builder().setLength(
            DegreesProp.Builder(stepDegrees * DASH_GAP_RATIO / 2f).build(),
        ).build()
        val flatCap = StrokeCapProp.Builder().setValue(STROKE_CAP_BUTT).build()
        val litDashes = ceil(remainingRatio * DASH_COUNT - RATIO_TOLERANCE).toInt()
        val arc = Arc.Builder()
            .setAnchorAngle(DegreesProp.Builder(0f).build())
            .setAnchorType(ARC_ANCHOR_START)
        repeat(DASH_COUNT) { index ->
            val lit = index >= DASH_COUNT - litDashes
            arc.addContent(halfGap)
            arc.addContent(
                ArcLine.Builder()
                    .setLength(DegreesProp.Builder(stepDegrees * (1f - DASH_GAP_RATIO)).build())
                    .setThickness(dp(DASH_THICKNESS_DP))
                    .setStrokeCap(flatCap)
                    .setColor(argb(if (lit) COLOR_DASH_ON else COLOR_DASH_OFF))
                    .build(),
            )
            arc.addContent(halfGap)
        }
        return arc.build()
    }

    /**
     * The thick ring, starting at 12 o'clock and going clockwise: a dimmed part for the elapsed time, then
     * the white part of the remaining time, which ends at 12 o'clock and shrinks clockwise.
     */
    private fun progressArc(remainingRatio: Float): LayoutElement = Arc.Builder()
        .setAnchorAngle(DegreesProp.Builder(0f).build())
        .setAnchorType(ARC_ANCHOR_START)
        .addContent(progressSegment((1f - remainingRatio) * FULL_CIRCLE_DEGREES, COLOR_PROGRESS_OFF))
        .addContent(progressSegment(remainingRatio * FULL_CIRCLE_DEGREES, COLOR_PROGRESS))
        .build()

    private fun progressSegment(lengthDegrees: Float, color: Int): ArcLine = ArcLine.Builder()
        .setLength(DegreesProp.Builder(lengthDegrees).build())
        .setThickness(dp(PROGRESS_THICKNESS_DP))
        .setStrokeCap(StrokeCapProp.Builder().setValue(STROKE_CAP_BUTT).build())
        .setColor(argb(color))
        .build()
}

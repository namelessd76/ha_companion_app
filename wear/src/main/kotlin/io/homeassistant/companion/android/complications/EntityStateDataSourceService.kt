package io.homeassistant.companion.android.complications

import android.graphics.Color
import android.graphics.drawable.Icon
import androidx.annotation.StringRes
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dagger.hilt.android.AndroidEntryPoint
import io.github.timoptr.mdiicons.toBitmap
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.data.integration.display.EntitiesForDisplayManager
import io.homeassistant.companion.android.common.data.integration.display.awaitLoadedOrNull
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.wear.EntityStateComplicationsDao
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import timber.log.Timber

private const val COMPLICATION_ICON_SIZE_DP = 24

private const val PRICE_LOW_MAX = 15.0 // below this: green
private const val PRICE_MEDIUM_MAX = 20.0 // below this: orange, at/above: red

private const val COLOR_LOW = 0xFF43A047.toInt() // green
private const val COLOR_MEDIUM = 0xFFFB8C00.toInt() // orange
private const val COLOR_HIGH = 0xFFE53935.toInt() // red


@AndroidEntryPoint
class EntityStateDataSourceService : SuspendingComplicationDataSourceService() {

    @Inject
    lateinit var serverManager: ServerManager

    @Inject
    lateinit var entityStateComplicationsDao: EntityStateComplicationsDao

    @Inject
    lateinit var entitiesForDisplayManager: EntitiesForDisplayManager

    fun colorForPrice(price: Float): Int = when {
        price <= PRICE_LOW_MAX -> COLOR_LOW
        price <= PRICE_MEDIUM_MAX -> COLOR_MEDIUM
        else -> COLOR_HIGH
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT &&
            request.complicationType != ComplicationType.LONG_TEXT &&
            request.complicationType != ComplicationType.RANGED_VALUE
        ) {
            return null
        }

        val settings = entityStateComplicationsDao.get(request.complicationInstanceId)
        val entityId = settings?.entityId
            ?: return getErrorComplication(request, R.string.complication_entity_invalid, true)

        val loadedState = entitiesForDisplayManager.snapshot(ServerManager.SERVER_ID_ACTIVE, listOf(entityId))
            .awaitLoadedOrNull() ?: return getErrorComplication(request, R.string.state_unknown)

        val displayEntity = loadedState.entity(entityId)
            ?: return getErrorComplication(request, R.string.complication_entity_invalid)

        val number = displayEntity.rawState.toFloatOrNull() ?: 0f
        val iconBitmap = displayEntity.icon.toBitmap(this, COMPLICATION_ICON_SIZE_DP, colorForPrice(number))

        val title = if (settings.showTitle) {
            PlainComplicationText.Builder(displayEntity.name).build()
        } else {
            null
        }

        val text = PlainComplicationText.Builder(
            displayEntity.state.resolve(this, withUnit = settings.showUnit),
        ).build()

        val contentDescription = PlainComplicationText.Builder(
            getText(R.string.complication_entity_state_content_description),
        ).build()
        val monochromaticImage = MonochromaticImage.Builder(Icon.createWithBitmap(iconBitmap)).build()
        val tapAction = ComplicationReceiver.getComplicationToggleIntent(this, request.complicationInstanceId)

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = text,
                    contentDescription = contentDescription,
                )
                    .setTitle(title)
                    .setTapAction(tapAction)
                    .setMonochromaticImage(monochromaticImage)
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = text,
                    contentDescription = contentDescription,
                )
                    .setTitle(title)
                    .setTapAction(tapAction)
                    .setMonochromaticImage(monochromaticImage)
                    .build()
            }
            ComplicationType.RANGED_VALUE -> {
                // The range comes from the entity attributes, which are not part of the display entity
                val attributes = serverManager.integrationRepository().getEntity(entityId)?.attributes.orEmpty()
                val minValue = floor((attributes["min"] as? Number)?.toFloat() ?: 0f)
                val maxValue = max(30f, ceil((attributes["max"] as? Number)?.toFloat() ?: 30f))
                RangedValueComplicationData.Builder(
                    value = number.coerceIn(minValue, maxValue),
                    min = minValue,
                    max = maxValue,
                    contentDescription = contentDescription,
                )
                    .setTitle(title)
                    .setText(text)
                    .setTapAction(tapAction)
                    .setMonochromaticImage(monochromaticImage)
                    .build()
            }
            else -> null // Already handled at the start of the function
        }
    }

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
        return if (request.complicationType == ComplicationType.SHORT_TEXT) {
            ShortTextComplicationData.Builder(
                text = text,
                contentDescription = contentDescription,
            ).setTapAction(tapAction).build()
        } else {
            LongTextComplicationData.Builder(
                text = text,
                contentDescription = contentDescription,
            ).setTapAction(tapAction).build()
        }
    }
}

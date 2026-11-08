package io.homeassistant.companion.android.common.data.prefs.impl.entities

import io.homeassistant.companion.android.common.util.kotlinJsonMapper
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiabloWorldBossTileConfigTest {

    @Test
    fun `Given a config When serializing and deserializing Then it is unchanged`() {
        val config = DiabloWorldBossTileConfig(
            referenceSpawnEpochMillis = 1_793_000_000_000L,
            notificationsEnabled = true,
            quietHoursEnabled = false,
            quietStartMinuteOfDay = 23 * 60,
            quietEndMinuteOfDay = 7 * 60 + 30,
        )

        val decoded = kotlinJsonMapper.decodeFromString<DiabloWorldBossTileConfig>(
            kotlinJsonMapper.encodeToString(config),
        )

        assertEquals(config, decoded)
    }

    @Test
    fun `Given default config When reading quiet hours Then they span midnight to 8`() {
        val config = DiabloWorldBossTileConfig()

        assertEquals(LocalTime.MIDNIGHT, config.quietStart)
        assertEquals(LocalTime.of(8, 0), config.quietEnd)
    }
}

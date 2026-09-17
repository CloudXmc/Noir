package moe.meowrealms.noir

import moe.meowrealms.noir.data.PlayerData
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerAnnouncementPreferenceTest {
    @Test
    fun `old player data without preference defaults to enabled`() {
        val data = PlayerData.GSON.fromJson("{}", PlayerData::class.java)

        assertTrue(data.isBroadcastReceptionEnabled())
    }

    @Test
    fun `personal announcement preference survives serialization`() {
        val data = PlayerData.GSON.fromJson("{}", PlayerData::class.java)

        assertFalse(data.toggleBroadcastReception())
        val restored = PlayerData.GSON.fromJson(PlayerData.GSON.toJson(data), PlayerData::class.java)

        assertFalse(restored.isBroadcastReceptionEnabled())
        assertTrue(restored.toggleBroadcastReception())
        assertTrue(restored.isBroadcastReceptionEnabled())
    }

    @Test
    fun `temporary 1_2_3 preference field migrates without losing opt out`() {
        val data = PlayerData.GSON.fromJson(
            "{\"personal_announcements_enabled\":false}",
            PlayerData::class.java
        )

        assertFalse(data.isBroadcastReceptionEnabled())
    }
}

package moe.meowrealms.noir

import moe.meowrealms.noir.i18n.AnnouncementDeliveryPolicy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnnouncementDeliveryPolicyTest {
    @Test
    fun `global announcements reach online players by default`() {
        assertTrue(AnnouncementDeliveryPolicy.shouldDeliver(
            announcementsEnabled = true,
            playerOnline = true,
            personalReceptionEnabled = true
        ))
    }

    @Test
    fun `personal opt out blocks only that recipient`() {
        assertFalse(AnnouncementDeliveryPolicy.shouldDeliver(
            announcementsEnabled = true,
            playerOnline = true,
            personalReceptionEnabled = false
        ))
        assertTrue(AnnouncementDeliveryPolicy.shouldDeliver(
            announcementsEnabled = true,
            playerOnline = true,
            personalReceptionEnabled = true
        ))
    }
}

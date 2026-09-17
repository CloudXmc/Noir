package moe.meowrealms.noir.i18n

/** 全服状态广播的纯判断逻辑，便于验证默认开启和个人退订行为。 */
object AnnouncementDeliveryPolicy {
    fun shouldDeliver(
        announcementsEnabled: Boolean,
        playerOnline: Boolean,
        personalReceptionEnabled: Boolean
    ): Boolean = announcementsEnabled && playerOnline && personalReceptionEnabled
}

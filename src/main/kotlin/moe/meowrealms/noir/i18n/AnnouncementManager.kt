package moe.meowrealms.noir.i18n

import moe.meowrealms.noir.NoirMain
import moe.meowrealms.noir.data.PlayerDataStorage.getNoirData
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player

/**
 * YSM 状态提示，仅发送给产生该状态的玩家。
 */
object AnnouncementManager {
    @Volatile
    private var enabled: Boolean = true

    fun load(configuration: FileConfiguration) {
        this.enabled = configuration.getBoolean("announcements.enabled", true)
        NoirMain.instance.slF4JLogger.info(
            "Personal announcement settings loaded: enabled={}",
            this.enabled
        )
    }

    fun sendTo(player: Player, key: String, subKeys: List<String> = emptyList(), args: List<Any> = emptyList()) {
        if (!this.enabled || !player.isOnline) {
            return
        }

        val message = runCatching {
            NoirMain.languageManager.i18n(key, subKeys, args)
        }.getOrElse { throwable ->
            NoirMain.instance.slF4JLogger.error("Failed to build announcement for language key $key", throwable)
            return
        }

        // 先取得在线玩家快照，再逐个进入接收者的实体上下文读取个人偏好并发送。
        // 某个玩家关闭接收只影响自己，不会关闭其他玩家的全服公屏。
        val recipients = org.bukkit.Bukkit.getOnlinePlayers().toList()
        for (recipient in recipients) {
            NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(recipient).run(Runnable {
                val personalReceptionEnabled = recipient.getNoirData()?.isBroadcastReceptionEnabled() ?: true
                if (AnnouncementDeliveryPolicy.shouldDeliver(
                        announcementsEnabled = this.enabled,
                        playerOnline = recipient.isOnline,
                        personalReceptionEnabled = personalReceptionEnabled
                    )) {
                    recipient.sendMessage(message)
                }
            }, null)
        }
    }

}

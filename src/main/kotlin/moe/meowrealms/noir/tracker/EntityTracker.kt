package moe.meowrealms.noir.tracker

import io.papermc.paper.event.player.PlayerTrackEntityEvent
import io.papermc.paper.event.player.PlayerUntrackEntityEvent
import moe.meowrealms.noir.NoirMain
import moe.meowrealms.noir.network.ClientConnectionManager.getYsmConnectionOrNull
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object EntityTracker : Listener{
    private val trackerVisibleMap: MutableMap<UUID, MutableSet<UUID>> = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    fun init() {
        Bukkit.getPluginManager().registerEvents(this, NoirMain.instance)
    }

    /** PlugMan reload 后追踪事件不会为已有关系重放，主动从 Paper 追踪快照重建。 */
    fun rebuildCurrentTracking() {
        for (tracked in Bukkit.getOnlinePlayers().toList()) {
            NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(tracked).run(Runnable {
                if (!tracked.isOnline) return@Runnable
                val trackedId = tracked.uniqueId
                for (watcher in tracked.trackedBy) {
                    this.trackerVisibleMap.computeIfAbsent(watcher.uniqueId) {
                        ConcurrentHashMap.newKeySet<UUID>()
                    }.add(trackedId)
                }
            }, null)
        }
    }

    fun shutdown() {
        this.trackerVisibleMap.clear()
    }

    @EventHandler
    fun onPlayerTrackEntity(trackEvent: PlayerTrackEntityEvent) {
        val tracker = trackEvent.getPlayer()
        val tracked = trackEvent.entity

        if (tracked is Player) {
            // 追踪事件来自不同 Region，集合本身也必须支持并发读写。
            val visibleList = this.trackerVisibleMap.computeIfAbsent(tracker.uniqueId) {
                ConcurrentHashMap.newKeySet<UUID>()
            }

            if (visibleList.add(tracked.uniqueId)) {
                this.handlePairingAdd(tracker, tracked)
            }
        }
    }

    fun getVisible(of: Player): Set<Player> {
        val visibleList = this.trackerVisibleMap[of.uniqueId]

        if (visibleList != null) {
            val result = mutableSetOf<Player>()

            for (uuid in visibleList) {
                val target = Bukkit.getPlayer(uuid)

                if (target != null) {
                    result.add(target)
                }
            }

            return result
        }

        return emptySet()
    }

    /** 返回当前玩家的观察者 UUID，用于重连后向已在追踪该玩家的客户端补发状态。 */
    fun getWatchers(of: Player): Set<UUID> {
        val watchedId = of.uniqueId
        return this.trackerVisibleMap.asSequence()
            .filter { (_, visible) -> visible.contains(watchedId) }
            .map { (watcherId, _) -> watcherId }
            .toSet()
    }

    fun handlePairingAdd(owner: Player, watched: Player) {
        // PlayerTrackEntityEvent 运行在观察者区域；先切回被观察玩家的实体上下文读取其状态。
        NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(watched).run(Runnable {
            val watchedConnection = watched.getYsmConnectionOrNull() ?: return@Runnable
            if (!watchedConnection.isHandshakeConfirmed()) return@Runnable
            watchedConnection.syncModelFullDataTo(owner)
        }, null)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val quitId = event.player.uniqueId
        this.trackerVisibleMap.remove(quitId)
        // 清理其他玩家视野中的旧 UUID，避免重连后沿用失效连接。
        this.trackerVisibleMap.values.forEach { it.remove(quitId) }
    }

    @EventHandler
    fun onPlayerUntrackEntity(untrackEvent: PlayerUntrackEntityEvent) {
        val watcher = untrackEvent.getPlayer()
        val untracked = untrackEvent.entity

        if (untracked is Player) {
            val visibleList = this.trackerVisibleMap[watcher.uniqueId] ?: return

            visibleList.remove(untracked.uniqueId)
        }
    }

}

package moe.meowrealms.noir.network

import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import io.netty.util.ReferenceCountUtil
import moe.meowrealms.noir.NoirMain
import moe.meowrealms.noir.network.packet.Packet
import moe.meowrealms.noir.data.PlayerDataStorage
import moe.meowrealms.noir.utils.SimpleFriendlyByteBuf
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.messaging.PluginMessageListener
import net.kyori.adventure.text.Component
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object ClientConnectionManager : Listener, PluginMessageListener {
    const val VERSION = "2.6.0"
    private val LISTENING_CHANNEL = NamespacedKey("yes_steve_model", VERSION.replace(".", "_"))
    private val LISTENING_CHANNEL_STR = LISTENING_CHANNEL.toString()

    fun channelName(): String = LISTENING_CHANNEL_STR

    private val connectedPlayers : MutableMap<UUID, ClientConnection> = ConcurrentHashMap()

    fun init() {
        Bukkit.getPluginManager().registerEvents(this, NoirMain.instance)

        Bukkit.getMessenger().registerIncomingPluginChannel(NoirMain.instance, LISTENING_CHANNEL_STR, this)
        Bukkit.getMessenger().registerOutgoingPluginChannel(NoirMain.instance, LISTENING_CHANNEL_STR)

        NoirMain.instance.slF4JLogger.info("Initialized network manager.")
    }

    /** PlugMan reload 后 PlayerJoinEvent 不会再次触发，主动重建在线玩家连接并发起握手。 */
    fun reconnectOnlinePlayers() {
        for (player in Bukkit.getOnlinePlayers().toList()) {
            NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(player).run(Runnable {
                if (!player.isOnline || !PlayerDataStorage.initializeOnlinePlayer(player)) {
                    NoirMain.instance.slF4JLogger.warn("Skipping YSM reconnect for {} because player data is not loaded", player.name)
                    return@Runnable
                }

                val old = this.connectedPlayers.remove(player.uniqueId)
                old?.onDisconnected()
                val connection = ClientConnection(player)
                this.connectedPlayers[player.uniqueId] = connection
                try {
                    connection.onConnected()
                    NoirMain.instance.slF4JLogger.info("Reconnected YSM bridge for online player {} after reload", player.name)
                } catch (throwable: Throwable) {
                    this.connectedPlayers.remove(player.uniqueId, connection)
                    try {
                        connection.onDisconnected()
                    } catch (cleanupError: Throwable) {
                        throwable.addSuppressed(cleanupError)
                    }
                    this.connectionExceptionCaught(player, throwable)
                }
            }, null)
        }
    }

    /**
     * 取消所有玩家的模型同步任务。插件重载/关闭时必须清理，避免旧连接继续持有玩家和插件引用。
     */
    fun shutdown() {
        connectedPlayers.values.forEach { connection ->
            try {
                connection.onDisconnected()
            } catch (throwable: Throwable) {
                NoirMain.instance.slF4JLogger.error("Failed to close YSM connection", throwable)
            }
        }
        connectedPlayers.clear()

        // 显式注销频道，兼容没有完整执行 Bukkit 插件卸载清理的 PlugMan 实现。
        Bukkit.getMessenger().unregisterIncomingPluginChannel(NoirMain.instance, LISTENING_CHANNEL_STR, this)
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(NoirMain.instance, LISTENING_CHANNEL_STR)
    }

    /**
     * 插件卸载前踢出所有已完成 YSM 握手的玩家，避免客户端继续保留失效的模型状态。
     * Folia 下必须回到每个玩家自己的实体上下文；任务在 shutdown 清理连接后仍可安全执行。
     */
    fun kickAllYsmPlayersOnDisable(reason: Component) {
        val targets = this.connectedPlayers.entries
            .filter { (_, connection) -> connection.isHandshakeConfirmed() }
            .mapNotNull { (uuid, _) -> Bukkit.getPlayer(uuid) }

        for (player in targets) {
            val kickTask = Runnable {
                if (player.isOnline) {
                    player.kick(reason)
                }
            }

            try {
                if (NoirMain.isFolia()) {
                    NoirMain.instance.morePaperLib.scheduling()
                        .entitySpecificScheduler(player)
                        .run(kickTask, null)
                } else {
                    kickTask.run()
                }
            } catch (throwable: Throwable) {
                NoirMain.instance.slF4JLogger.error(
                    "Failed to kick YSM player ${player.name} while disabling Noir",
                    throwable
                )
            }
        }

        if (targets.isNotEmpty()) {
            NoirMain.instance.slF4JLogger.info(
                "Scheduled {} YSM player(s) to be kicked because Noir is disabling",
                targets.size
            )
        }
    }

    // note: packets are coming from netty threads
    override fun onPluginMessageReceived(
        channel: String,
        player: Player,
        message: ByteArray
    ) {
        if (channel != LISTENING_CHANNEL_STR) {
            return
        }

        // 玩家退出与插件消息可能并发到达；没有活动连接时直接丢弃旧数据包，
        // 避免为已经失效的实体创建任务并刷屏警告。
        if (!this.connectedPlayers.containsKey(player.uniqueId)) {
            return
        }

        // push back to main thread
        // so that we could manage the states of a single connection safely and easily
        NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(player).run(Runnable {
            try {
                val payload = YsmPayloadFraming.decode(message)
                val messageAsBuffer = SimpleFriendlyByteBuf(Unpooled.wrappedBuffer(payload))
                try {
                    this.onClientMessage(player, messageAsBuffer)
                } finally {
                    ReferenceCountUtil.safeRelease(messageAsBuffer)
                }
            } catch (throwable: Throwable) {
                this.connectionExceptionCaught(player, throwable)
            }
        }, null)
    }

    @EventHandler
    fun onPlayerJoinEvent(event: PlayerJoinEvent) {
        NoirMain.instance.slF4JLogger.info("Player {} has connected with UUID {}", event.player.name, event.player.uniqueId)

        val newConnection = ClientConnection(event.player)
        this.connectedPlayers.put(event.player.uniqueId, newConnection)?.let { old ->
            try {
                old.onDisconnected()
            } catch (throwable: Throwable) {
                this.connectionExceptionCaught(event.player, throwable)
            }
        }

        // we are already on the target thread, no need to schedule back

        // catch and pass through exceptions
        try {
            newConnection.onConnected()
        }catch (throwable : Throwable){
            this.connectedPlayers.remove(event.player.uniqueId, newConnection)
            try {
                newConnection.onDisconnected()
            } catch (cleanupError: Throwable) {
                throwable.addSuppressed(cleanupError)
            }
            this.connectionExceptionCaught(event.player, throwable)
        }
    }

    @EventHandler
    fun onPlayerQuitEvent(event: PlayerQuitEvent) {
        val disconnected = this.connectedPlayers.remove(event.player.uniqueId)

        if (disconnected != null) {
            // catch and pass through exceptions
            try {
                disconnected.onDisconnected()
            }catch (throwable : Throwable){
                this.connectionExceptionCaught(event.player, throwable)
            }

            NoirMain.instance.slF4JLogger.info("Disconnected connection for player {}", event.player.name)
        }
    }

    fun connectionExceptionCaught(player: Player, throwable: Throwable) {
        NoirMain.instance.slF4JLogger.error("Error has caught during packet processing! Packet sender is ${player.name}.", throwable)
    }

    fun restartModelSynchronizationForConnectedPlayers() {
        for ((playerUuid, connection) in this.connectedPlayers) {
            val player = Bukkit.getPlayer(playerUuid) ?: continue

            NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(player).run(Runnable {
                try {
                    connection.restartModelSynchronizationAfterReload()
                } catch (throwable: Throwable) {
                    this.connectionExceptionCaught(player, throwable)
                }
            }, null)
        }
    }

    /** 返回已完成 YSM 握手的在线玩家名称快照。 */
    fun getOnlineYsmPlayerNames(): List<String> {
        return this.connectedPlayers.values
            .mapNotNull { it.onlineYsmName() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** 返回已确认未安装/未启用 YSM 的在线玩家名称快照。 */
    fun getOnlineNonYsmPlayerNames(): List<String> {
        return this.connectedPlayers.values
            .mapNotNull { it.onlineNonYsmName() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** 返回已完成 YSM 握手的在线玩家 UUID 快照，供命令逐个进入实体上下文处理。 */
    fun getOnlineYsmPlayerUuids(): List<UUID> {
        return this.connectedPlayers
            .filterValues { it.onlineYsmName() != null }
            .keys
            .toList()
    }

    fun sendMessage(receiver: Player, packet: Packet) {
        try {
            val packetId = NoirMain.packetRegistry.lookupForId(packet)

            if (packetId == -1) {
                NoirMain.instance.slF4JLogger.warn("Sending unknown packet of id: {}", packetId)
                return
            }

            val tempBuffer = SimpleFriendlyByteBuf(Unpooled.buffer())
            val encodedPacket: ByteArray

            try {
                tempBuffer.writeByte(packetId)
                packet.write(tempBuffer)
                encodedPacket = ByteArray(tempBuffer.readableBytes())
                tempBuffer.readBytes(encodedPacket)
            } finally {
                ReferenceCountUtil.safeRelease(tempBuffer)
            }

            // Folia 的网络发送通过目标实体调度器进入其所有者上下文；Paper 直接发送即可。
            // Fabric YSM 的 YSMPayload codec 会写入外层 VarInt 长度；Bukkit API 不会自动补充。
            val framedPacket = YsmPayloadFraming.encode(encodedPacket)
            val sendTask = Runnable {
                if (receiver.isOnline) {
                    receiver.sendPluginMessage(NoirMain.instance, LISTENING_CHANNEL_STR, framedPacket)
                }
            }
            if (NoirMain.isFolia()) {
                NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(receiver).run(sendTask, null)
            } else {
                sendTask.run()
            }
        }catch (throwable : Throwable){
            this.connectionExceptionCaught(receiver, throwable)
        }
    }

    fun onClientMessage(sender: Player, packetData: SimpleFriendlyByteBuf) {
        val connection = this.connectedPlayers[sender.uniqueId]

        if (connection == null) {
            // 连接建立/退出与插件消息存在正常竞态；这里丢弃过期数据包即可。
            return
        }

        try {
            val packetInt = packetData.readByte().toInt()

            val createdPacket = NoirMain.packetRegistry.lookupAndConstruct(packetInt)
                ?: throw DecoderException("Packet with id $packetInt not found!")

            val direction = createdPacket.direction()
            val receivingDirection = connection.receivingDirection()

            if (direction != receivingDirection) {
                throw DecoderException("Handling packets with mismatched direction $direction! Expected direction is $receivingDirection!")
            }

            createdPacket.read(packetData)

            if (packetData.readableBytes() != 0) {
                throw DecoderException("Packet $packetInt has ${packetData.readableBytes()} unread bytes")
            }

            createdPacket.handle(connection)
        } catch (throwable: Throwable) {
            this.connectionExceptionCaught(sender, throwable)
        }
    }

    /** 获取当前连接；玩家刚进服或退出时可能暂时不存在，调用方必须允许 null。 */
    fun Player.getYsmConnectionOrNull(): ClientConnection? {
        return connectedPlayers[this.uniqueId]
    }

}

package moe.meowrealms.noir.network

import moe.meowrealms.noir.NoirConstants
import moe.meowrealms.noir.NoirMain
import moe.meowrealms.noir.data.PlayerDataStorage.getNoirData
import moe.meowrealms.noir.data.PlayerDataStorage
import moe.meowrealms.noir.i18n.AnnouncementManager
import moe.meowrealms.noir.model.ModelManager
import moe.meowrealms.noir.network.ClientConnectionManager.getYsmConnectionOrNull
import moe.meowrealms.noir.network.packet.Packet
import moe.meowrealms.noir.network.packet.PacketHandler
import moe.meowrealms.noir.network.packet.c2s.C2SAnimationRequestPacket
import moe.meowrealms.noir.network.packet.c2s.C2SHandshakeConfirmedPacket
import moe.meowrealms.noir.network.packet.c2s.C2SModelDataPayload
import moe.meowrealms.noir.network.packet.c2s.C2SModelSwitchRequestPacket
import moe.meowrealms.noir.network.packet.c2s.C2SMolangExecuteRequestPacket
import moe.meowrealms.noir.network.packet.c2s.C2SMolangExpressionValueSyncPacket
import moe.meowrealms.noir.network.packet.c2s.C2SMolangFeedbackPacket
import moe.meowrealms.noir.network.packet.c2s.C2SStarModelPacket
import moe.meowrealms.noir.network.packet.s2c.S2CAuthModelListPacket
import moe.meowrealms.noir.network.packet.s2c.S2CEntityModelAnimationDataPacket
import moe.meowrealms.noir.network.packet.s2c.S2CEntityModelSelectionDataPacket
import moe.meowrealms.noir.network.packet.s2c.S2CHandshakeRequestPacket
import moe.meowrealms.noir.network.packet.s2c.S2CMolangExecutePacket
import moe.meowrealms.noir.network.packet.s2c.S2CMolangExpressionValueSyncPacket
import moe.meowrealms.noir.network.packet.s2c.S2CStarModelListPacket
import moe.meowrealms.noir.network.sync.ModelSynchronizationContext
import moe.meowrealms.noir.tracker.EntityTracker
import org.bukkit.entity.Player
import space.arim.morepaperlib.scheduling.ScheduledTask

class ClientConnection (
    private val player: Player,
): PacketHandler {
    private val playerName: String = player.name
    private lateinit var synchronizationContext: ModelSynchronizationContext
    private var tickTask: ScheduledTask? = null
    private var handshakeTask: ScheduledTask? = null
    private var dataPersistTask: ScheduledTask? = null
    @Volatile
    private var handshakeConfirmed: Boolean = false
    private var handshakeAttempts: Int = 0
    private var noClientAnnounced: Boolean = false
    private var clientRejectedAnnounced: Boolean = false
    private var synchronizationStartedAnnounced: Boolean = false
    private var synchronizationFinishedAnnounced: Boolean = false
    private var synchronizationFailedAnnounced: Boolean = false
    @Volatile
    private var joined: Boolean = false

    /** 仅握手完成的 YSM 客户端允许接收 YSM 数据。 */
    fun isHandshakeConfirmed(): Boolean = this.handshakeConfirmed

    /** 只返回不可变快照，供控制台查询，避免把 Player 实时对象带出实体上下文。 */
    fun onlineYsmName(): String? {
        return if (this.joined && this.handshakeConfirmed) this.playerName else null
    }

    /** 只有握手等待结束且未收到 YSM 响应时，才确认玩家未安装/未启用 YSM。 */
    fun onlineNonYsmName(): String? {
        return if (this.joined && !this.handshakeConfirmed && this.noClientAnnounced) this.playerName else null
    }

    fun onConnected() {
        this.joined = true
        this.synchronizationContext = ModelManager.createNewModelSynchronizationContext(this.player)

        this.scheduleHandshakeUntilChannelReady()

        // kick-start ticking
        NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(this.player).runAtFixedRate(Runnable{
            this.tickConnection()
        }, null, 1L, 1L)?.let {
            this.tickTask = it
        }
    }

    fun onDisconnected() {
        this.joined = false
        if (this::synchronizationContext.isInitialized) {
            this.synchronizationContext.cleanup()
        }

        this.tickTask?.cancel()
        this.handshakeTask?.cancel()
        this.dataPersistTask?.cancel()
        this.tickTask = null
        this.handshakeTask = null
        this.dataPersistTask = null
    }

    /**
     * PlayerJoinEvent 可能早于 Fabric 客户端发送 minecraft:register；等待频道注册后再发握手。
     */
    private fun scheduleHandshakeUntilChannelReady() {
        this.handshakeTask = NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(this.player)
            .runAtFixedRate(Runnable {
                if (!this.joined || !this.player.isOnline) {
                    this.handshakeTask?.cancel()
                    return@Runnable
                }
                if (this.handshakeConfirmed || this.handshakeAttempts++ >= 100) {
                    if (!this.handshakeConfirmed && !this.noClientAnnounced) {
                        this.noClientAnnounced = true
                        AnnouncementManager.sendTo(this.player,
                            NoirConstants.LanguageConstants.ANNOUNCEMENT_NO_CLIENT,
                            listOf("player"),
                            listOf(this.player.name)
                        )
                    }
                    this.handshakeTask?.cancel()
                    return@Runnable
                }
                if (!this.player.getListeningPluginChannels().contains(ClientConnectionManager.channelName())) {
                    return@Runnable
                }

                NoirMain.instance.slF4JLogger.info("Sending handshake to player ${this.player.uniqueId}")
                this.send(S2CHandshakeRequestPacket(ClientConnectionManager.VERSION))
                this.handshakeTask?.cancel()
            }, null, 1L, 1L)
    }

    fun restartModelSynchronizationAfterReload() {
        if (!this.handshakeConfirmed) {
            return
        }

        this.synchronizationContext.restart()

        val playerData = this.player.getNoirData()

        playerData?.validateAndCorrectModelSelection(
            NoirConstants.ModelDefaults.DEFAULT_MODEL_ID,
            NoirConstants.ModelDefaults.DEFAULT_MODEL_TEXTURE
        )

        this.syncModelSubscribes()
        this.syncModelSelectionData()
    }

    private fun syncEntityStatesToAnimationData() {
        val playerData = this.player.getNoirData() ?: return
        val animationData = playerData.animationData

        animationData.health(this.player.health.toInt())
        animationData.expLevel(this.player.exp.toInt())
        animationData.foodLevel(this.player.foodLevel)
        animationData.flying(this.player.isFlying)
    }

    private fun checkAndSyncAnimationState() {
        val playerData = this.player.getNoirData() ?: return

        if (playerData.animationData.tickPacketSyncRequired) {
            playerData.animationData.tickPacketSyncRequired = false
            this.syncModelAnimationData()
        }
    }

    fun tickConnection() {
        this.syncEntityStatesToAnimationData()

        this.checkAndSyncAnimationState()
    }

    fun syncModelSubscribes() {
        val playerData = this.player.getNoirData() ?: return

        this.send(S2CAuthModelListPacket(ModelManager.getAuthRequiredModels()))
        this.send(S2CStarModelListPacket(playerData.staredModels))
    }

    fun syncModelSelectionDataTo(player: Player) {
        // 非 YSM 客户端继续使用原版皮肤，不发送自定义模型数据。
        if (!this.handshakeConfirmed) return
        val targetConnection = player.getYsmConnectionOrNull() ?: return
        if (!targetConnection.isHandshakeConfirmed()) return

        val playerData = this.player.getNoirData() ?: return

        targetConnection.send(S2CEntityModelSelectionDataPacket(
            this.player.entityId,
            playerData.selectedModelId,
            playerData.selectedModelTexture,
            playerData.disabled,
            playerData.animationData
        ))
    }

    /** 切换模型或重连时清除临时动作，并恢复该模型自己的齿轮设置。 */
    fun resetAndRestoreModelState() {
        val playerData = this.player.getNoirData() ?: return
        playerData.animationData = playerData.createModelState(this.player)
        this.syncEntityStatesToAnimationData()

        val hashId = ModelManager.getModelHashId(playerData.selectedModelId) ?: return

        // 即使该模型从未保存过设置，也要下发一份空的全量变量表。
        // 客户端收到全量包才会建立 RoamingStruct，那是齿轮改动回传服务端的唯一出口；
        // 少发这一个包，这个模型的设置就永远不会被保存下来。
        val restoreVariables = playerData.molangVariablesForRestore(hashId)
        playerData.animationData.setFull()
        playerData.animationData.molangVars(hashId, restoreVariables)
    }

    fun syncModelAnimationDataTo(player: Player) {
        if (!this.handshakeConfirmed) return
        val targetConnection = player.getYsmConnectionOrNull() ?: return
        if (!targetConnection.isHandshakeConfirmed()) return

        val playerData = this.player.getNoirData() ?: return

        targetConnection.send(S2CEntityModelAnimationDataPacket(
            playerData.animationData
        ))
    }

    fun syncModelFullDataTo(player: Player) {
        this.syncModelSelectionDataTo(player)
        this.syncModelAnimationDataTo(player)
    }

    fun syncModelFullData() {
        this.syncModelFullDataTo(this.player)

        for (visible in EntityTracker.getVisible(this.player)) {
            this.syncModelFullDataTo(visible)
        }
    }

    fun syncModelSelectionData() {
        this.syncModelSelectionDataTo(this.player)

        for (visible in EntityTracker.getVisible(this.player)) {
            this.syncModelSelectionDataTo(visible)
        }
    }

    fun syncModelAnimationData() {
        this.syncModelAnimationDataTo(this.player)

        for (visible in EntityTracker.getVisible(this.player)) {
            this.syncModelAnimationDataTo(visible)
        }
    }

    fun handleHandshakeCallback() {
        if (!this.synchronizationStartedAnnounced) {
            this.synchronizationStartedAnnounced = true
            AnnouncementManager.sendTo(this.player,
                NoirConstants.LanguageConstants.ANNOUNCEMENT_SYNC_STARTED,
                listOf("player"),
                listOf(this.player.name)
            )
        }

        this.resetAndRestoreModelState()
        this.synchronizationContext.begin()

        // 握手可能晚于 PlayerTrackEntityEvent；此前追踪事件发送给未握手目标时会被丢弃。
        // 握手完成后重新补发自己和当前视野内所有玩家的全量状态，确保双方都能看到模型与动作。
        this.syncModelFullDataTo(this.player)

        // 玩家重连后，观察者侧可能不会再次触发 PlayerTrackEntityEvent。
        // 主动向仍在追踪该实体的 YSM 客户端补发自己的模型和动作状态。
        for (watcherUuid in EntityTracker.getWatchers(this.player)) {
            val watcher = org.bukkit.Bukkit.getPlayer(watcherUuid) ?: continue
            this.syncModelFullDataTo(watcher)
        }

        // 把当前视野内玩家的状态补发给刚完成握手的客户端。
        for (visible in EntityTracker.getVisible(this.player)) {
            visible.getYsmConnectionOrNull()?.syncModelFullDataTo(this.player)
        }

        // model subscribes
        this.syncModelSubscribes()
    }

    fun broadcastMolangExecute(expression: String) {
        val toSend = S2CMolangExecutePacket(intArrayOf(this.player.entityId), expression)

        // ourself
        this.send(toSend)

        // others
        for (target in EntityTracker.getVisible(this.player)) {
            target.getYsmConnectionOrNull()?.send(toSend)
        }
    }

    override fun receivingDirection(): EnumDirection {
        return EnumDirection.C_T_S
    }

    override fun sendingDirection(): EnumDirection {
        return EnumDirection.S_T_C
    }

    override fun send(packet: Packet) {
        ClientConnectionManager.sendMessage(this.player, packet)
    }

    override fun handleClientModelSyncPayload(packet: C2SModelDataPayload) {
        this.synchronizationContext.handleClientReply(packet.payload)
    }

    override fun handleHandshakeConfirmed(packet: C2SHandshakeConfirmedPacket) {
        if (this.handshakeConfirmed) {
            return
        }

        // 服务端只和配套发布的最新客户端握手。
        // 标识不匹配时不置 handshakeConfirmed、不触发回调，模型同步不会开始；
        // 只给玩家一条提示，不踢人、不动任何玩家数据。
        if (!NoirConstants.ClientRequirements.isAcceptedClient(packet.clientBrand)) {
            if (!this.clientRejectedAnnounced) {
                this.clientRejectedAnnounced = true
                AnnouncementManager.sendTo(this.player,
                    NoirConstants.LanguageConstants.ANNOUNCEMENT_CLIENT_REJECTED,
                    listOf("player", "client_brand", "required_brand"),
                    listOf(
                        this.player.name,
                        packet.clientBrand.ifBlank { "unknown" },
                        NoirConstants.ClientRequirements.REQUIRED_CLIENT_BRAND
                    )
                )
            }
            NoirMain.instance.slF4JLogger.info(
                "Rejected handshake from player ${this.player.name}: client brand '${packet.clientBrand}' " +
                    "does not match required '${NoirConstants.ClientRequirements.REQUIRED_CLIENT_BRAND}'"
            )
            return
        }

        this.handshakeConfirmed = true

        if (packet.version != ClientConnectionManager.VERSION) {
            AnnouncementManager.sendTo(this.player,
                NoirConstants.LanguageConstants.ANNOUNCEMENT_VERSION_MISMATCH,
                listOf("player", "version", "server_version"),
                listOf(this.player.name, packet.version, ClientConnectionManager.VERSION)
            )
        }

        NoirMain.instance.slF4JLogger.info("Received handshake confirmed packet from player ${this.player.name}. Begin model synchronization")

        this.handleHandshakeCallback()
    }

    fun announceSynchronizationSuccess() {
        if (this.synchronizationFinishedAnnounced || this.synchronizationFailedAnnounced) {
            return
        }

        this.synchronizationFinishedAnnounced = true
        AnnouncementManager.sendTo(this.player,
            NoirConstants.LanguageConstants.ANNOUNCEMENT_SYNC_SUCCESS,
            listOf("player"),
            listOf(this.player.name)
        )
    }

    fun announceSynchronizationFailure() {
        if (this.synchronizationFinishedAnnounced || this.synchronizationFailedAnnounced) {
            return
        }

        this.synchronizationFailedAnnounced = true
        AnnouncementManager.sendTo(this.player,
            NoirConstants.LanguageConstants.ANNOUNCEMENT_SYNC_FAILED,
            listOf("player"),
            listOf(this.player.name)
        )
    }

    override fun handleModelSwitchRequest(packet: C2SModelSwitchRequestPacket) {
        val validate = ModelManager.validateSelectedModel(packet.modelId, packet.textureId)

        if (!validate.left || !validate.right) {
            NoirMain.instance.slF4JLogger.warn("Player ${this.player.name} is trying switching to an unknown model ${packet.modelId} with texture ${packet.textureId}!")
            return
        }

        val playerData = this.player.getNoirData() ?: return

        val previousModelId = playerData.selectedModelId
        val modelChanged = previousModelId != packet.modelId
        playerData.selectedModelId = packet.modelId
        playerData.selectedModelTexture = packet.textureId
        if (modelChanged) {
            // 只切换当前模型指针，原模型的齿轮设置继续保留，切回时自动恢复。
            playerData.onModelSwitched(previousModelId)
        }

        playerData.markDirty()
        PlayerDataStorage.persistAsync(this.player)

        this.resetAndRestoreModelState()
        this.syncModelSelectionData()
    }

    override fun handleAnimationRequest(packet: C2SAnimationRequestPacket) {
        val playerData = this.player.getNoirData() ?: return

        if (packet.entityId != -1) {
            // TODO - This is for TLM, not ours to process
            return
        }

        if (packet.animationIndex == -1) {
            playerData.animationData.extraAnimation("")

            this.syncModelAnimationData()
            return
        }

        val selectionValidated = playerData.validateModelSelection()
        if (!selectionValidated) {
            NoirMain.instance.slF4JLogger.warn("Player ${this.player.name} is trying playing animations with an invalid model selection ${playerData.selectedModelId} - ${playerData.selectedModelTexture}!")
            return
        }

        val animationLookup = ModelManager.lookupAnimationFromPacket(playerData.selectedModelId, packet.animationIndex, packet.category)
        if (animationLookup == null) {
            NoirMain.instance.slF4JLogger.warn("Player ${this.player.name} is trying playing an invalid animation ${packet.category} - ${packet.animationIndex}!")
            return
        }

        playerData.animationData.extraAnimation(animationLookup)
        this.syncModelAnimationData()
    }

    override fun handleStarModel(packet: C2SStarModelPacket) {
        val playerData = this.player.getNoirData() ?: return

        if (packet.add) {
            playerData.staredModels.add(packet.modelId)
        } else {
            playerData.staredModels.remove(packet.modelId)
        }

        playerData.markDirty()

        // Velocity 跨子服切换时，目标子服的登录早于本服的退出保存；
        // 收藏状态必须在本服提前落库，否则切换子服后会回退。
        this.schedulePlayerDataPersist()
    }

    /**
     * 高频改动合并写盘：停止操作 1 秒后保存一次。
     * 必须在玩家实体上下文调用，任务在断开连接时统一取消。
     */
    private fun schedulePlayerDataPersist() {
        this.dataPersistTask?.cancel()
        this.dataPersistTask = NoirMain.instance.morePaperLib.scheduling()
            .entitySpecificScheduler(this.player).runDelayed(Runnable {
                this.dataPersistTask = null
                if (this.joined && this.player.isOnline) {
                    PlayerDataStorage.persistAsync(this.player)
                }
            }, null, 20L)
    }

    override fun handleMolangExecuteRequest(packet: C2SMolangExecuteRequestPacket) {
        // uhm 好吧我也不知道ysm在数据包里留个这个是干啥的,按理来说sender都能获取到应该也用不着这个
        // 可能是给TLM留的 ?()
        if (packet.onEntityId != this.player.entityId) {
            return
        }

        this.broadcastMolangExecute(packet.expression)
    }

    override fun handleMolangExpressionValueSyncPacket(packet: C2SMolangExpressionValueSyncPacket) {
        val toSend = S2CMolangExpressionValueSyncPacket(this.player.entityId, packet.expressionValues)

        this.send(toSend)
        for (watching in EntityTracker.getVisible(this.player)) {
            watching.getYsmConnectionOrNull()?.send(toSend)
        }
    }

    override fun handleMolangFeedback(packet: C2SMolangFeedbackPacket) {
        val playerData = this.player.getNoirData() ?: return
        val feedback = packet.feedback
        val expectedHashId = ModelManager.getModelHashId(playerData.selectedModelId)
        if (feedback.entityId != this.player.entityId || expectedHashId == null || feedback.modelHashId != expectedHashId) {
            NoirMain.instance.slF4JLogger.warn(
                "Ignoring invalid Molang feedback from ${this.player.name}: entity=${feedback.entityId}, hash=${feedback.modelHashId}"
            )
            return
        }

        if (!playerData.mergeMolangVariables(feedback.modelHashId, feedback.variables)) {
            NoirMain.instance.slF4JLogger.warn(
                "Ignoring excessive Molang variables from ${this.player.name} for model hash ${feedback.modelHashId}"
            )
            return
        }
        playerData.animationData.molangVars(feedback.modelHashId, feedback.variables)
        this.syncModelAnimationData()

        // 滑块可能高频上传；停止调整 1 秒后合并写盘，退出时存储监听器仍会保存脏数据。
        this.schedulePlayerDataPersist()
    }
}

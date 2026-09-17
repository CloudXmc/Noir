package moe.meowrealms.noir

import it.unimi.dsi.fastutil.floats.FloatArrayList
import moe.meowrealms.noir.command.NoirCommand
import moe.meowrealms.noir.command.NoirTabCompleter
import moe.meowrealms.noir.data.PlayerDataStorage
import moe.meowrealms.noir.i18n.I18NManager
import moe.meowrealms.noir.i18n.AnnouncementManager
import moe.meowrealms.noir.model.ModelManager
import moe.meowrealms.noir.network.ClientConnectionManager
import moe.meowrealms.noir.network.data.DispatchServerDrivenProperty
import moe.meowrealms.noir.network.packet.PacketRegistry
import moe.meowrealms.noir.network.packet.c2s.*
import moe.meowrealms.noir.network.packet.s2c.*
import moe.meowrealms.noir.tracker.EntityTracker
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.HandlerList
import space.arim.morepaperlib.MorePaperLib


class NoirMain : JavaPlugin() {
    val morePaperLib: MorePaperLib = MorePaperLib(this)

    companion object {
        lateinit var instance: NoirMain
        lateinit var packetRegistry: PacketRegistry
        val languageManager: I18NManager = I18NManager()
        var inited = false

        // 只在插件启动早期检测一次；Folia 下不能用 isPrimaryThread 判断区域所有权。
        @Volatile
        private var foliaDetected: Boolean? = null

        @Synchronized
        fun isFolia(): Boolean {
            foliaDetected?.let { return it }
            val detected = try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
                true
            } catch (_: ClassNotFoundException) {
                false
            }
            foliaDetected = detected
            return detected
        }
    }

    fun registerPackets() {
        packetRegistry.register(51, S2CHandshakeRequestPacket::class) { S2CHandshakeRequestPacket() }
        packetRegistry.register(52, C2SHandshakeConfirmedPacket::class) { C2SHandshakeConfirmedPacket() }

        packetRegistry.register(1, S2CModelDataPayloadPacket::class) { S2CModelDataPayloadPacket(ByteArray(0)) }
        packetRegistry.register(2, C2SModelDataPayload::class) { C2SModelDataPayload(ByteArray(0)) }

        packetRegistry.register(4, S2CEntityModelSelectionDataPacket::class) {
            S2CEntityModelSelectionDataPacket(
                0,
                "",
                "",
                false,
                DispatchServerDrivenProperty(0)
            )
        }
        packetRegistry.register(21, S2CEntityModelAnimationDataPacket::class) {
            S2CEntityModelAnimationDataPacket(
                DispatchServerDrivenProperty(0)
            )
        }

        packetRegistry.register(3, S2CMolangExecutePacket::class) { S2CMolangExecutePacket(IntArray(0), "") }
        packetRegistry.register(5, C2SModelSwitchRequestPacket::class) { C2SModelSwitchRequestPacket("", "") }
        packetRegistry.register(7, C2SAnimationRequestPacket::class) { C2SAnimationRequestPacket(0, "", 0) }
        packetRegistry.register(6, S2CAuthModelListPacket::class) { S2CAuthModelListPacket(HashSet()) }
        packetRegistry.register(8, S2CStarModelListPacket::class) { S2CStarModelListPacket(HashSet()) }
        packetRegistry.register(9, C2SStarModelPacket::class) { C2SStarModelPacket("", true) }
        packetRegistry.register(15, C2SMolangFeedbackPacket::class) { C2SMolangFeedbackPacket() }
        packetRegistry.register(17, C2SMolangExecuteRequestPacket::class) { C2SMolangExecuteRequestPacket("", 0) }
        packetRegistry.register(19, S2CMolangExpressionValueSyncPacket::class) { S2CMolangExpressionValueSyncPacket(0, FloatArrayList()) }
        packetRegistry.register(18, C2SMolangExpressionValueSyncPacket::class) { C2SMolangExpressionValueSyncPacket(FloatArrayList()) }
    }

    fun initNetworking() {
        packetRegistry = PacketRegistry()

        this.registerPackets()

        ClientConnectionManager.init()
    }

    fun initAndLoadModels(){
        this.slF4JLogger.info("Begin loading models")

        ModelManager.setWorkingDir(this.dataPath)

        ModelManager.initEnv()

        val loadBegin = System.nanoTime()

        ModelManager.loadModels()

        val timeElapsed = System.nanoTime() - loadBegin

        this.slF4JLogger.info("Model loading took ${timeElapsed / 1000_000L} ms")
        this.slF4JLogger.info("All models has been loaded! Currently has ${ModelManager.getLoadedModelCount()} models loaded.")
    }

    fun initDataStorage() {
        PlayerDataStorage.setWorkingDir(this.dataPath)

        PlayerDataStorage.init(this.config)

        this.slF4JLogger.info("Data storage initialized.")
    }

    fun initEntityTracker() {
        EntityTracker.init()

        this.slF4JLogger.info("Entity tracker initialized.")
    }

    fun initI18n() {
        languageManager.initialize(this.dataPath, "zh_CN")

        this.slF4JLogger.info("I18n initialized.")
    }

    fun initConfig() {
        this.saveDefaultConfig()

        // 旧版本已经存在 config.yml 时，补齐新版本默认节点但不覆盖服主已有值。
        this.config.options().copyDefaults(true)
        this.saveConfig()

        val cfg = this.config
        AnnouncementManager.load(cfg)
        NoirConstants.ModelSyncConstants.PER_PLAYER_RATE_LIMIT_MBPS =
            cfg.getDouble("model-sync.per-player-rate-limit-mbps", 8.0)
        NoirConstants.ModelSyncConstants.GLOBAL_RATE_LIMIT_MBPS =
            cfg.getDouble("model-sync.global-rate-limit-mbps", 40.0)
        NoirConstants.ModelDefaults.DEFAULT_MODEL_ID =
            cfg.getString("model-defaults.model-id", "default")!!
        NoirConstants.ModelDefaults.DEFAULT_MODEL_TEXTURE =
            cfg.getString("model-defaults.texture", "default")!!

        this.slF4JLogger.info("Config loaded: per-player=${NoirConstants.ModelSyncConstants.PER_PLAYER_RATE_LIMIT_MBPS}Mbps, global=${NoirConstants.ModelSyncConstants.GLOBAL_RATE_LIMIT_MBPS}Mbps, defaultModel=${NoirConstants.ModelDefaults.DEFAULT_MODEL_ID}/${NoirConstants.ModelDefaults.DEFAULT_MODEL_TEXTURE}")
    }

    fun initCommands() {
        val command = this.getCommand("noir")
            ?: throw IllegalStateException("plugin.yml 中缺少 noir 指令声明")
        command.setExecutor(NoirCommand())
        command.tabCompleter = NoirTabCompleter()

        this.slF4JLogger.info("Commands initialized.")
    }

    override fun onEnable() {
        instance = this

        // Folia 检测必须早于网络与调度器初始化，并且整个生命周期只执行一次。
        val folia = isFolia()
        this.slF4JLogger.info("Detected server mode: ${if (folia) "Folia" else "Paper"}")

        this.initConfig()
        this.initI18n()
        this.initNetworking()
        this.initAndLoadModels()
        this.initDataStorage()
        this.initEntityTracker()
        EntityTracker.rebuildCurrentTracking()
        this.initCommands()

        inited = true
        // PlugMan reload 不会重新触发 PlayerJoinEvent，异步加载在线玩家数据后补建 YSM 连接。
        PlayerDataStorage.loadOnlinePlayers {
            ClientConnectionManager.reconnectOnlinePlayers()
        }
    }

    override fun onDisable() {
        // 先踢出已完成握手的 YSM 玩家，防止热卸载后客户端继续持有失效模型状态。
        try {
            ClientConnectionManager.kickAllYsmPlayersOnDisable(
                languageManager.i18nRaw(NoirConstants.LanguageConstants.KICK_YSM_REASON)
            )
        } catch (throwable: Throwable) {
            this.slF4JLogger.error("Failed to kick YSM players while disabling Noir", throwable)
        }

        // 先停止连接任务，再保存玩家数据，避免禁用后仍有回调访问旧插件实例。
        ClientConnectionManager.shutdown()
        EntityTracker.shutdown()
        PlayerDataStorage.shutdown()
        HandlerList.unregisterAll(this)
        inited = false
    }
}

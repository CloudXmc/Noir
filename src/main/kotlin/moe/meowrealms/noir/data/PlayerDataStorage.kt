package moe.meowrealms.noir.data

import moe.meowrealms.noir.NoirConstants
import moe.meowrealms.noir.NoirMain
import moe.meowrealms.noir.data.storage.DatabaseSettings
import moe.meowrealms.noir.data.storage.MySqlPlayerDataRepository
import moe.meowrealms.noir.data.storage.PlayerIdentityMode
import moe.meowrealms.noir.data.storage.PlayerIdentitySettings
import org.bukkit.Bukkit
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

// TODO 这块让ai写的()
object PlayerDataStorage : Listener {
    private val loaded: MutableMap<UUID, PlayerData> = ConcurrentHashMap()
    private val identityKeys: ConcurrentHashMap<UUID, String> = ConcurrentHashMap()
    private val lastKnownNames: ConcurrentHashMap<UUID, String> = ConcurrentHashMap()

    private val operationTails: ConcurrentHashMap<UUID, CompletableFuture<Void>> = ConcurrentHashMap()
    private val pendingSaves: MutableSet<CompletableFuture<Void>> = ConcurrentHashMap.newKeySet()

    private lateinit var dataDir: Path
    private lateinit var asyncExecutor: Executor
    private val lifecycleLock = Any()
    @Volatile private var active: Boolean = false
    @Volatile private var databaseSettings: DatabaseSettings? = null
    @Volatile private var identitySettings: PlayerIdentitySettings = PlayerIdentitySettings(
        mode = PlayerIdentityMode.OFFLINE_NAME,
        offlineNameIgnoreCase = true,
        saveLastKnownName = true
    )
    @Volatile private var repository: MySqlPlayerDataRepository? = null
    @Volatile private var databaseInitialization: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    fun setWorkingDir(dir: Path) {
        this.dataDir = dir.resolve("player_data").also {
            Files.createDirectories(it)
        }
    }

    fun init(configuration: FileConfiguration) {
        synchronized(this.lifecycleLock) {
            this.active = true
        }
        this.asyncExecutor = NoirMain.instance.morePaperLib.scheduling().asyncScheduler()
        this.identitySettings = try {
            PlayerIdentitySettings.from(configuration)
        } catch (throwable: Throwable) {
            NoirMain.instance.slF4JLogger.error(
                "玩家身份配置无效，继续使用默认 OFFLINE_NAME 模式。",
                throwable
            )
            this.identitySettings
        }

        // 建连和建表在异步线程执行；玩家预登录加载会等待这次初始化完成。
        this.databaseInitialization = CompletableFuture.runAsync({
            try {
                if (!this.active) return@runAsync
                this.reloadDatabase(configuration)
            } catch (throwable: Throwable) {
                NoirMain.instance.slF4JLogger.error(
                    "MySQL 初始化失败，Noir 将继续使用本地 player_data 文件。",
                    throwable
                )
            }
        }, this.asyncExecutor)

        Bukkit.getPluginManager().registerEvents(this, NoirMain.instance)
    }

    data class DatabaseSwitchResult(
        val changed: Boolean,
        val mysqlEnabled: Boolean,
        val identityChanged: Boolean
    )

    /**
     * 必须从异步线程调用。先验证新连接并建表，成功后才原子切换；失败时旧连接保持不变。
     */
    fun reloadDatabase(configuration: FileConfiguration): DatabaseSwitchResult {
        if (!this.active) {
            throw IllegalStateException("Noir 数据存储当前未处于启用状态")
        }

        val newSettings = DatabaseSettings.from(configuration)
        val newIdentitySettings = PlayerIdentitySettings.from(configuration)
        val databaseChanged = newSettings != this.databaseSettings
        val identityChanged = newIdentitySettings != this.identitySettings
        if (!databaseChanged && !identityChanged) {
            return DatabaseSwitchResult(false, newSettings.enabled, false)
        }

        if (databaseChanged) {
            val newRepository = if (newSettings.enabled) {
                MySqlPlayerDataRepository(newSettings).also {
                    it.initialize()
                    it.testConnection()
                }
            } else {
                null
            }

            val oldRepository = synchronized(this.lifecycleLock) {
                if (!this.active) {
                    newRepository?.close()
                    throw IllegalStateException("Noir 在数据库切换完成前已被禁用")
                }
                val old = this.repository
                this.repository = newRepository
                this.databaseSettings = newSettings
                old
            }

            // 切换前已经开始的玩家操作继续结束，再关闭旧连接池。
            val oldOperations = this.operationTails.values.toTypedArray()
            CompletableFuture.allOf(*oldOperations).whenCompleteAsync({ _, _ ->
                oldRepository?.close()
            }, this.asyncExecutor)

            NoirMain.instance.slF4JLogger.info(
                if (newSettings.enabled) {
                    "MySQL 玩家模型共享存储已启用，数据表为 ${newSettings.tableName}。"
                } else {
                    "MySQL 玩家模型共享存储已关闭，继续使用本地 player_data 文件。"
                }
            )
        }
        this.identitySettings = newIdentitySettings
        if (identityChanged) {
            NoirMain.instance.slF4JLogger.warn(
                "玩家身份模式已修改，旧玩家数据不会自动迁移。新模式将在玩家下次进入时生效。"
            )
        }
        return DatabaseSwitchResult(databaseChanged, newSettings.enabled, identityChanged)
    }

    fun saveAll() {
        loaded.forEach { (uuid, data) ->
            if (data.isDirty()) {
                val playerKey = identityKeys[uuid] ?: return@forEach
                enqueueSave(uuid, playerKey, lastKnownNames[uuid], data.persistenceCopy())
            }
        }

        val allFutures = pendingSaves.toTypedArray<CompletableFuture<Void>>()
        try {
            CompletableFuture.allOf(*allFutures).join()
        } catch (e: Exception) {
            NoirMain.instance.slF4JLogger.error("Some saves failed during shutdown", e)
        }
    }

    /** 关闭时保存并释放玩家对象、Future 链和待保存集合。 */
    fun shutdown() {
        synchronized(this.lifecycleLock) {
            this.active = false
        }
        this.saveAll()
        this.loaded.clear()
        this.identityKeys.clear()
        this.lastKnownNames.clear()
        this.operationTails.clear()
        this.pendingSaves.clear()
        synchronized(this.lifecycleLock) {
            this.repository?.close()
            this.repository = null
            this.databaseSettings = null
        }
    }

    private fun resolveDataFile(uuid: UUID): Path = dataDir.resolve("$uuid.json")

    private fun doSaveLocal(uuid: UUID, data: PlayerData) {
        val file = resolveDataFile(uuid)
        Files.writeString(
            file,
            PlayerData.GSON.toJson(data),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )
    }

    private fun doLoadLocal(uuid: UUID): PlayerData? {
        val file = resolveDataFile(uuid)
        if (!Files.exists(file)) return null
        val json = Files.readString(file)
        return PlayerData.GSON.fromJson(json, PlayerData::class.java)
    }

    private fun awaitDatabaseInitialization() {
        try {
            this.databaseInitialization.join()
        } catch (throwable: Throwable) {
            NoirMain.instance.slF4JLogger.error(
                "等待 MySQL 初始化失败，继续使用本地玩家数据。",
                throwable
            )
        }
    }

    private fun doLoad(uuid: UUID, playerKey: String, playerName: String): PlayerData? {
        this.awaitDatabaseInitialization()
        val activeRepository = this.repository
        if (activeRepository != null) {
            try {
                val databaseData = activeRepository.load(playerKey)
                if (databaseData != null) {
                    return databaseData
                }

                // 第一次启用 MySQL 时自动导入该玩家现有本地数据，不删除本地文件。
                val localData = this.doLoadLocal(uuid)
                if (localData != null) {
                    activeRepository.save(
                        playerKey,
                        playerName.takeIf { identitySettings.saveLastKnownName },
                        localData
                    )
                    return localData
                }
                return null
            } catch (throwable: Throwable) {
                NoirMain.instance.slF4JLogger.error(
                    "从 MySQL 读取玩家 $uuid 失败，临时使用本地数据。",
                    throwable
                )
            }
        }
        return this.doLoadLocal(uuid)
    }

    private fun doSave(uuid: UUID, playerKey: String, playerName: String?, data: PlayerData) {
        this.awaitDatabaseInitialization()
        val activeRepository = this.repository
        if (activeRepository != null) {
            try {
                activeRepository.save(
                    playerKey,
                    playerName.takeIf { identitySettings.saveLastKnownName },
                    data
                )
            } catch (throwable: Throwable) {
                NoirMain.instance.slF4JLogger.error(
                    "保存玩家 $uuid 到 MySQL 失败，已保留本地备份。",
                    throwable
                )
            }
        }

        // 始终维护本地备份；MySQL 暂时不可用时玩家数据不会直接丢失。
        this.doSaveLocal(uuid, data)
        data.markClean()
    }

    private fun enqueueOp(uuid: UUID, task: Runnable): CompletableFuture<Void> {
        val newFuture = CompletableFuture<Void>()

        operationTails.compute(uuid) { _, oldTail ->
            val chain = if (oldTail == null || oldTail.isDone) {
                CompletableFuture.runAsync(task, asyncExecutor)
            } else {
                oldTail.thenRunAsync(task, asyncExecutor)
            }

            chain.whenComplete { _, ex ->
                if (ex != null) newFuture.completeExceptionally(ex)
                else newFuture.complete(null)
                operationTails.remove(uuid, chain)
            }
            chain
        }

        return newFuture
    }

    private fun enqueueLoad(uuid: UUID, playerKey: String, playerName: String): CompletableFuture<PlayerData?> {
        val resultFuture = CompletableFuture<PlayerData?>()
        val task = Runnable {
            try {
                val fromDisk = doLoad(uuid, playerKey, playerName)
                val data = fromDisk ?: PlayerData().also {
                    NoirMain.instance.slF4JLogger.info("No existing data for $uuid, creating new")
                }
                loaded[uuid] = data
                NoirMain.instance.slF4JLogger.info("Loaded player data for $uuid")
                resultFuture.complete(data)
            } catch (e: Exception) {
                NoirMain.instance.slF4JLogger.error("Failed to load player data for $uuid", e)
                resultFuture.completeExceptionally(e)
            }
        }

        enqueueOp(uuid, task)
        return resultFuture
    }

    /** PlugMan reload 不会触发登录事件，需要为仍在线的玩家补建数据会话。 */
    fun loadOnlinePlayers(onComplete: () -> Unit) {
        val players = Bukkit.getOnlinePlayers().toList()
        if (players.isEmpty()) {
            NoirMain.instance.morePaperLib.scheduling().globalRegionalScheduler().run(Runnable(onComplete))
            return
        }

        val remaining = AtomicInteger(players.size)
        players.forEach { player ->
            // UUID、名称等 Player 状态也在玩家实体上下文获取，异步阶段只传递不可变快照。
            NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(player).run(Runnable {
                if (!player.isOnline) {
                    if (remaining.decrementAndGet() == 0) {
                        NoirMain.instance.morePaperLib.scheduling().globalRegionalScheduler().run(Runnable(onComplete))
                    }
                    return@Runnable
                }
                val uuid = player.uniqueId
                val playerName = player.name
                val playerKey = this.identitySettings.resolveKey(uuid, playerName)
                this.identityKeys[uuid] = playerKey
                this.lastKnownNames[uuid] = playerName
                this.enqueueLoad(uuid, playerKey, playerName).whenComplete { _, throwable ->
                    if (throwable != null) {
                        NoirMain.instance.slF4JLogger.error("Failed to reload data for online player $uuid", throwable)
                    }
                    if (remaining.decrementAndGet() == 0) {
                        NoirMain.instance.morePaperLib.scheduling().globalRegionalScheduler().run(Runnable(onComplete))
                    }
                }
            }, null)
        }
    }

    /** 在玩家实体上下文初始化重载后补建的 PlayerData。 */
    fun initializeOnlinePlayer(player: Player): Boolean {
        val data = this.loaded[player.uniqueId] ?: return false
        data.validateAndCorrectModelSelection(
            NoirConstants.ModelDefaults.DEFAULT_MODEL_ID,
            NoirConstants.ModelDefaults.DEFAULT_MODEL_TEXTURE
        )
        data.initSubComponents(player)
        return true
    }

    private fun enqueueSave(
        uuid: UUID,
        playerKey: String,
        playerName: String?,
        data: PlayerData
    ): CompletableFuture<Void> {
        val task = Runnable {
            try {
                doSave(uuid, playerKey, playerName, data)
                NoirMain.instance.slF4JLogger.info("Saved player data for $uuid")
            } catch (e: Exception) {
                NoirMain.instance.slF4JLogger.error("Failed to save player data for $uuid", e)
                throw e
            }
        }

        val future = enqueueOp(uuid, task)
        pendingSaves.add(future)

        future.whenComplete { _, _ -> pendingSaves.remove(future) }
        return future
    }

    /** 在玩家实体上下文调用，先建立不可变数据副本，再异步立即写入共享存储。 */
    fun persistAsync(player: Player) {
        val data = loaded[player.uniqueId] ?: return
        val playerKey = identityKeys[player.uniqueId] ?: return
        enqueueSave(player.uniqueId, playerKey, player.name, data.persistenceCopy())
    }

    @EventHandler
    fun onPlayerPreJoin(event: AsyncPlayerPreLoginEvent) {
        val uuid = event.uniqueId
        val playerName = event.name
        val playerKey = this.identitySettings.resolveKey(uuid, playerName)
        this.identityKeys[uuid] = playerKey
        this.lastKnownNames[uuid] = playerName

        val oldTail = operationTails[uuid]
        if (oldTail != null && !oldTail.isDone) {
            try {
                oldTail.join()
            } catch (e: Exception) {
                NoirMain.instance.slF4JLogger.error("Previous operation failed for $uuid, continuing anyway", e)
            }
        }

        try {
            enqueueLoad(uuid, playerKey, playerName).join()
        } catch (e: Exception) {
            NoirMain.instance.slF4JLogger.error("Failed to load data for $uuid", e)
        }
    }

    @EventHandler
    fun onPlayerJoined(event: PlayerJoinEvent) {
        val player = event.player
        check(this.initializeOnlinePlayer(player)) {
            "Player data missing at join for ${player.uniqueId}!"
        }
    }

    @EventHandler
    fun onPlayerQuited(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        val data = loaded.remove(uuid) ?: return
        val playerKey = identityKeys.remove(uuid)
        val playerName = lastKnownNames.remove(uuid) ?: event.player.name

        if (data.isDirty() && playerKey != null) {
            enqueueSave(uuid, playerKey, playerName, data.persistenceCopy())
        }
    }

    fun Player.getNoirData(): PlayerData? = loaded[uniqueId]
}

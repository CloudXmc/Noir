package moe.meowrealms.noir.command

import moe.meowrealms.noir.NoirConstants
import moe.meowrealms.noir.NoirMain
import moe.meowrealms.noir.data.PlayerDataStorage
import moe.meowrealms.noir.data.PlayerDataStorage.getNoirData
import moe.meowrealms.noir.i18n.AnnouncementManager
import moe.meowrealms.noir.model.ModelManager
import moe.meowrealms.noir.network.ClientConnectionManager
import moe.meowrealms.noir.network.ClientConnectionManager.getYsmConnectionOrNull
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class NoirCommand : CommandExecutor {
    private val subcommands: Map<String, (CommandSender, Array<out String>) -> Unit> = mapOf(
        "help" to ::executeHelp,
        "reload" to ::executeReload,
        "setmodel" to ::executeSetModel,
        "players" to ::executePlayers,
        "broadcast" to ::executeBroadcast,
        "kickysm" to ::executeKickYsm
    )

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        val isBroadcastCommand = args.firstOrNull()?.equals("broadcast", ignoreCase = true) == true
        val isKickYsmCommand = args.firstOrNull()?.equals("kickysm", ignoreCase = true) == true
        if (!sender.hasPermission(NoirConstants.PermissionConstants.NOIR_COMMAND) &&
            !(isBroadcastCommand && sender.hasPermission(NoirConstants.PermissionConstants.BROADCAST_COMMAND)) &&
            !(isKickYsmCommand && sender.hasPermission(NoirConstants.PermissionConstants.KICK_YSM_COMMAND))) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.NO_PERMISSION)
            return true
        }

        if (args.isEmpty()) {
            this.executeHelp(sender, args)
            return true
        }

        val handler = subcommands[args[0].lowercase()]
        if (handler != null) {
            handler(sender, args)
        } else {
            this.sendI18n(sender, NoirConstants.LanguageConstants.COMMAND_UNKNOWN_SUBCOMMAND)
        }

        return true
    }

    // --- subcommands ---

    private fun executeHelp(sender: CommandSender, @Suppress("UNUSED_PARAMETER") args: Array<out String>) {
        this.sendI18n(sender, NoirConstants.LanguageConstants.COMMAND_HELP)
    }

    private fun executePlayers(sender: CommandSender, @Suppress("UNUSED_PARAMETER") args: Array<out String>) {
        if (!sender.hasPermission(NoirConstants.PermissionConstants.LIST_PLAYERS_COMMAND)) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.NO_PERMISSION)
            return
        }

        val names = ClientConnectionManager.getOnlineYsmPlayerNames()
        val nonYsmNames = ClientConnectionManager.getOnlineNonYsmPlayerNames()
        if (names.isEmpty() && nonYsmNames.isEmpty()) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.PLAYERS_EMPTY)
            return
        }

        if (names.isNotEmpty()) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.PLAYERS_LIST,
                listOf("count", "players"), listOf(names.size, names.joinToString(", ")))
        }
        if (nonYsmNames.isNotEmpty()) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.PLAYERS_NO_YSM_LIST,
                listOf("count", "players"), listOf(nonYsmNames.size, nonYsmNames.joinToString(", ")))
        }
    }

    private fun executeBroadcast(sender: CommandSender, @Suppress("UNUSED_PARAMETER") args: Array<out String>) {
        if (!sender.hasPermission(NoirConstants.PermissionConstants.BROADCAST_COMMAND)) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.NO_PERMISSION)
            return
        }

        if (sender !is Player) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.BROADCAST_RECEPTION_PLAYER_ONLY)
            return
        }

        // 玩家数据只能在该玩家的实体上下文修改；保存过程由存储层异步完成。
        NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(sender).run(Runnable {
            val playerData = sender.getNoirData()
            if (playerData == null) {
                this.sendI18n(sender, NoirConstants.LanguageConstants.BROADCAST_RECEPTION_NO_DATA)
                return@Runnable
            }

            val enabled = playerData.toggleBroadcastReception()
            playerData.markDirty()
            PlayerDataStorage.persistAsync(sender)
            this.sendI18n(sender, if (enabled) {
                NoirConstants.LanguageConstants.BROADCAST_RECEPTION_ENABLED
            } else {
                NoirConstants.LanguageConstants.BROADCAST_RECEPTION_DISABLED
            })
        }, null)
    }

    private fun executeKickYsm(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(NoirConstants.PermissionConstants.KICK_YSM_COMMAND)) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.NO_PERMISSION)
            return
        }
        if (args.size < 2) {
            val targets = ClientConnectionManager.getOnlineYsmPlayerUuids()
                .mapNotNull { Bukkit.getPlayer(it) }
            if (targets.isEmpty()) {
                this.sendI18n(sender, NoirConstants.LanguageConstants.KICK_YSM_NONE)
                return
            }

            val reason = NoirMain.languageManager.i18nRaw(NoirConstants.LanguageConstants.KICK_YSM_REASON)
            // 每个玩家必须回到自己的实体上下文，不能在命令线程批量直接 kick。
            targets.forEach { targetPlayer ->
                NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(targetPlayer).run(Runnable {
                    val connection = targetPlayer.getYsmConnectionOrNull()
                    if (targetPlayer.isOnline && connection?.isHandshakeConfirmed() == true) {
                        targetPlayer.kick(reason)
                    }
                }, null)
            }
            this.sendI18n(sender, NoirConstants.LanguageConstants.KICK_YSM_ALL_SUCCESS,
                listOf("count"), listOf(targets.size))
            return
        }

        val targetPlayer = Bukkit.getPlayer(args[1])
        if (targetPlayer == null) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.KICK_YSM_NOT_FOUND,
                listOf("player"), listOf(args[1]))
            return
        }

        // Player.kick 只能在目标玩家实体所属上下文执行，Folia 下不能从命令线程直接操作。
        NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(targetPlayer).run(Runnable {
            val connection = targetPlayer.getYsmConnectionOrNull()
            if (connection == null || !connection.isHandshakeConfirmed()) {
                this.sendI18n(sender, NoirConstants.LanguageConstants.KICK_YSM_NOT_YSM,
                    listOf("player"), listOf(targetPlayer.name))
                return@Runnable
            }

            val reason = NoirMain.languageManager.i18nRaw(NoirConstants.LanguageConstants.KICK_YSM_REASON)
            targetPlayer.kick(reason)
            this.sendI18n(sender, NoirConstants.LanguageConstants.KICK_YSM_SUCCESS,
                listOf("player"), listOf(targetPlayer.name))
        }, null)
    }

    private fun executeReload(sender: CommandSender, @Suppress("UNUSED_PARAMETER") args: Array<out String>) {
        if (!sender.hasPermission(NoirConstants.PermissionConstants.RELOAD_MODELS_COMMAND)) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.NO_PERMISSION)
            return
        }

        this.sendI18n(sender, NoirConstants.LanguageConstants.RELOAD_MODELS_STARTED)

        NoirMain.instance.morePaperLib.scheduling().asyncScheduler().run(Runnable {
            val loadBegin = System.nanoTime()

            try {
                // 配置与语言文件 IO 必须离开玩家实体/Region 线程。
                NoirMain.instance.reloadConfig()
                AnnouncementManager.load(NoirMain.instance.config)
                try {
                    val databaseSwitch = PlayerDataStorage.reloadDatabase(NoirMain.instance.config)
                    if (databaseSwitch.changed) {
                        this.sendI18n(
                            sender,
                            if (databaseSwitch.mysqlEnabled) {
                                NoirConstants.LanguageConstants.DATABASE_SWITCH_SUCCESS
                            } else {
                                NoirConstants.LanguageConstants.DATABASE_SWITCH_DISABLED
                            }
                        )
                    }
                    if (databaseSwitch.identityChanged) {
                        this.sendI18n(sender, NoirConstants.LanguageConstants.IDENTITY_CHANGED)
                    }
                } catch (throwable: Throwable) {
                    NoirMain.instance.slF4JLogger.error("Database reload failed; previous storage remains active", throwable)
                    this.sendI18n(
                        sender,
                        NoirConstants.LanguageConstants.DATABASE_SWITCH_FAILED,
                        listOf("reason"),
                        listOf(throwable.message ?: throwable.javaClass.simpleName)
                    )
                }
                // 语言文件位于 plugins/Noir/lang/，重载时先原子读取服主的修改。
                NoirMain.languageManager.loadLanguageFile("zh_CN")
                ModelManager.loadModels()

                val timeElapsed = (System.nanoTime() - loadBegin) / 1_000_000L
                val loadedCount = ModelManager.getLoadedModelCount()

                ClientConnectionManager.restartModelSynchronizationForConnectedPlayers()

                this.sendI18n(
                    sender,
                    NoirConstants.LanguageConstants.RELOAD_MODELS_SUCCESS,
                    listOf("count", "time_ms"),
                    listOf(loadedCount, timeElapsed)
                )

                NoirMain.instance.slF4JLogger.info("Reloaded models by command. Currently has $loadedCount models loaded.")
            } catch (throwable: Throwable) {
                NoirMain.instance.slF4JLogger.error("Failed to reload models by command!", throwable)

                this.sendI18n(
                    sender,
                    NoirConstants.LanguageConstants.RELOAD_MODELS_FAILED,
                    listOf("reason"),
                    listOf(throwable.message ?: throwable.javaClass.simpleName)
                )
            }
        })
    }

    private fun executeSetModel(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(NoirConstants.PermissionConstants.SET_MODEL_COMMAND)) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.NO_PERMISSION)
            return
        }

        if (args.size < 3) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.SET_MODEL_USAGE)
            return
        }

        val targetPlayer = Bukkit.getPlayer(args[1])
        if (targetPlayer == null) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.SET_MODEL_PLAYER_NOT_FOUND,
                listOf("player"), listOf(args[1]))
            return
        }

        val modelId = args[2]
        if (ModelManager.getModelData(modelId) == null) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.SET_MODEL_NOT_FOUND,
                listOf("model"), listOf(modelId))
            return
        }

        val textureId = if (args.size >= 4) args[3] else null
        val resolvedTexture = ModelManager.resolveTextureId(modelId, textureId)
        if (resolvedTexture == null) {
            this.sendI18n(sender, NoirConstants.LanguageConstants.SET_MODEL_TEXTURE_NOT_FOUND,
                listOf("texture", "model"), listOf(textureId ?: "null", modelId))
            return
        }

        // 目标玩家数据和网络同步必须在目标实体所属 Region 执行。
        NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(targetPlayer).run(Runnable {
            val playerData = targetPlayer.getNoirData()
            if (playerData == null) {
                this.sendI18n(sender, NoirConstants.LanguageConstants.SET_MODEL_PLAYER_NO_DATA,
                    listOf("player"), listOf(targetPlayer.name))
                return@Runnable
            }

            val previousModelId = playerData.selectedModelId
            val modelChanged = previousModelId != modelId
            playerData.selectedModelId = modelId
            playerData.selectedModelTexture = resolvedTexture
            if (modelChanged) {
                // 与客户端切换请求保持一致：不删除原模型的齿轮设置。
                playerData.onModelSwitched(previousModelId)
            }
            playerData.markDirty()
            PlayerDataStorage.persistAsync(targetPlayer)
            targetPlayer.getYsmConnectionOrNull()?.let { connection ->
                connection.resetAndRestoreModelState()
                connection.syncModelSelectionData()
            }

            this.sendI18n(sender, NoirConstants.LanguageConstants.SET_MODEL_SUCCESS,
                listOf("player", "model", "texture"), listOf(targetPlayer.name, modelId, resolvedTexture))
        }, null)
    }

    // --- utils ---

    private fun sendI18n(
        sender: CommandSender,
        key: String,
        subKeys: List<String> = emptyList(),
        args: List<Any> = emptyList()
    ) {
        val message = NoirMain.languageManager.i18n(key, subKeys, args)
        if (NoirMain.isFolia() && sender is org.bukkit.entity.Player) {
            NoirMain.instance.morePaperLib.scheduling().entitySpecificScheduler(sender).run(Runnable {
                sender.sendMessage(message)
            }, null)
        } else {
            sender.sendMessage(message)
        }
    }

}

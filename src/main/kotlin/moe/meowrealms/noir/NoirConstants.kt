package moe.meowrealms.noir

object NoirConstants {
    object ModelSyncConstants {
        // defaults, overridden by config.yml at startup
        @Volatile var PER_PLAYER_RATE_LIMIT_MBPS = 8.0
        @Volatile var GLOBAL_RATE_LIMIT_MBPS = 40.0
        const val MAX_CHUNK_BYTES = 30 * 1024
    }

    object ModelDefaults {
        // defaults, overridden by config.yml at startup
        @Volatile var DEFAULT_MODEL_ID = "default"
        @Volatile var DEFAULT_MODEL_TEXTURE = "default"
    }

    /**
     * 客户端准入要求。
     * 服务端只接受与本次发布配套的客户端：客户端在握手确认包里回传自己的构建标识，
     * 标识不匹配就不完成握手，也不会开始下发模型。
     * 旧客户端不写这一段，读出来是空串，同样会被拒绝。
     */
    object ClientRequirements {
        /** 当前随服务端一起发布的客户端构建标识，必须与客户端 NetworkHandler.CLIENT_BRAND 完全一致。 */
        const val REQUIRED_CLIENT_BRAND = "openysm:2.6.5.22"

        /** 只有完全匹配的客户端标识才允许完成握手。 */
        fun isAcceptedClient(brand: String?): Boolean {
            return brand == REQUIRED_CLIENT_BRAND
        }
    }

    object PermissionConstants {
        const val NOIR_COMMAND = "noir.command"
        const val RELOAD_MODELS_COMMAND = "noir.command.reload"
        const val SET_MODEL_COMMAND = "noir.command.setmodel"
        const val LIST_PLAYERS_COMMAND = "noir.command.players"
        const val BROADCAST_COMMAND = "noir.command.broadcast"
        const val KICK_YSM_COMMAND = "noir.command.kickysm"
    }

    object LanguageConstants {
        const val NO_PERMISSION = "noir.command.no_permission"
        const val COMMAND_HELP = "noir.command.help.broadcast_opt_out"
        const val COMMAND_UNKNOWN_SUBCOMMAND = "noir.command.unknown_subcommand"
        const val RELOAD_MODELS_STARTED = "noir.reload_models.started"
        const val RELOAD_MODELS_SUCCESS = "noir.reload_models.success"
        const val RELOAD_MODELS_FAILED = "noir.reload_models.failed"
        const val SET_MODEL_USAGE = "noir.set_model.usage"
        const val SET_MODEL_PLAYER_NOT_FOUND = "noir.set_model.player_not_found"
        const val SET_MODEL_NOT_FOUND = "noir.set_model.model_not_found"
        const val SET_MODEL_TEXTURE_NOT_FOUND = "noir.set_model.texture_not_found"
        const val SET_MODEL_PLAYER_NO_DATA = "noir.set_model.player_no_data"
        const val SET_MODEL_SUCCESS = "noir.set_model.success"
        const val PLAYERS_LIST = "noir.players.list"
        const val PLAYERS_NO_YSM_LIST = "noir.players.no_ysm_list"
        const val PLAYERS_EMPTY = "noir.players.empty"
        const val PLAYERS_PENDING = "noir.players.pending"
        const val BROADCAST_RECEPTION_ENABLED = "noir.broadcast_reception.saved_enabled"
        const val BROADCAST_RECEPTION_DISABLED = "noir.broadcast_reception.saved_disabled"
        const val BROADCAST_RECEPTION_PLAYER_ONLY = "noir.broadcast_reception.player_only"
        const val BROADCAST_RECEPTION_NO_DATA = "noir.broadcast_reception.no_data"
        const val KICK_YSM_USAGE = "noir.kickysm.usage"
        const val KICK_YSM_NOT_FOUND = "noir.kickysm.not_found"
        const val KICK_YSM_NOT_YSM = "noir.kickysm.not_ysm"
        const val KICK_YSM_SUCCESS = "noir.kickysm.success"
        const val KICK_YSM_ALL_SUCCESS = "noir.kickysm.all_success"
        const val KICK_YSM_NONE = "noir.kickysm.none"
        const val KICK_YSM_REASON = "noir.kickysm.reason"
        const val DATABASE_SWITCH_SUCCESS = "noir.database.switch_success"
        const val DATABASE_SWITCH_DISABLED = "noir.database.switch_disabled"
        const val DATABASE_SWITCH_FAILED = "noir.database.switch_failed"
        const val IDENTITY_CHANGED = "noir.identity.changed"
        const val ANNOUNCEMENT_NO_CLIENT = "noir.announcement.no_client"
        const val ANNOUNCEMENT_SYNC_STARTED = "noir.announcement.sync_started"
        const val ANNOUNCEMENT_SYNC_SUCCESS = "noir.announcement.sync_success"
        const val ANNOUNCEMENT_SYNC_FAILED = "noir.announcement.sync_failed"
        const val ANNOUNCEMENT_VERSION_MISMATCH = "noir.announcement.version_mismatch"
        const val ANNOUNCEMENT_CLIENT_REJECTED = "noir.announcement.client_rejected"
    }
}

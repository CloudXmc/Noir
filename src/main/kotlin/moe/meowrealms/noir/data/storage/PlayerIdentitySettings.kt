package moe.meowrealms.noir.data.storage

import org.bukkit.configuration.file.FileConfiguration
import java.util.Locale
import java.util.UUID

enum class PlayerIdentityMode {
    OFFLINE_NAME,
    ONLINE_UUID
}

data class PlayerIdentitySettings(
    val mode: PlayerIdentityMode,
    val offlineNameIgnoreCase: Boolean,
    val saveLastKnownName: Boolean
) {
    fun resolveKey(uuid: UUID, playerName: String): String {
        return when (mode) {
            PlayerIdentityMode.ONLINE_UUID -> "uuid:$uuid"
            PlayerIdentityMode.OFFLINE_NAME -> {
                val normalizedName = if (offlineNameIgnoreCase) {
                    playerName.lowercase(Locale.ROOT)
                } else {
                    playerName
                }
                "name:$normalizedName"
            }
        }
    }

    companion object {
        fun from(configuration: FileConfiguration): PlayerIdentitySettings {
            val rawMode = configuration.getString("identity.mode", "OFFLINE_NAME")!!
            val mode = try {
                PlayerIdentityMode.valueOf(rawMode.uppercase(Locale.ROOT))
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "identity.mode 只能为 OFFLINE_NAME 或 ONLINE_UUID"
                )
            }
            return PlayerIdentitySettings(
                mode = mode,
                offlineNameIgnoreCase = configuration.getBoolean("identity.offline-name-ignore-case", true),
                saveLastKnownName = configuration.getBoolean("identity.save-last-known-name", true)
            )
        }
    }
}

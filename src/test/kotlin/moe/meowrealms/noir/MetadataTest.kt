package moe.meowrealms.noir

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class MetadataTest {
    private val yaml = Yaml()

    @Test
    fun `plugin metadata declares Folia and help usage`() {
        val resource = javaClass.classLoader.getResourceAsStream("plugin.yml")
            ?: error("plugin.yml missing")
        resource.use {
            val metadata = yaml.load<Map<String, Any>>(it)
            assertEquals(true, metadata["folia-supported"])
            assertEquals("1.21", metadata["api-version"])
            val commands = metadata["commands"] as Map<*, *>
            val noir = commands["noir"] as Map<*, *>
            assertEquals("/noir help", noir["usage"])
        }
    }

    @Test
    fun `default configuration contains safe bandwidth limits`() {
        val resource = javaClass.classLoader.getResourceAsStream("config.yml")
            ?: error("config.yml missing")
        resource.use {
            val config = yaml.load<Map<String, Any>>(it)
            val sync = config["model-sync"] as Map<*, *>
            assertTrue((sync["per-player-rate-limit-mbps"] as Number).toDouble() >= 0.0)
            assertTrue((sync["global-rate-limit-mbps"] as Number).toDouble() >= 0.0)
            val announcements = config["announcements"] as Map<*, *>
            assertEquals(true, announcements["enabled"])
            assertTrue(!announcements.containsKey("broadcast-to-all"))
            val database = config["database"] as Map<*, *>
            assertEquals(false, database["enabled"])
            assertEquals(3306, database["port"])
            assertEquals("noir_player_data", database["table"])
            val pool = database["pool"] as Map<*, *>
            assertTrue((pool["maximum-size"] as Number).toInt() in 1..32)
            assertTrue((pool["minimum-idle"] as Number).toInt() >= 0)
            val identity = config["identity"] as Map<*, *>
            assertEquals("OFFLINE_NAME", identity["mode"])
            assertEquals(true, identity["offline-name-ignore-case"])
            assertEquals(true, identity["save-last-known-name"])
        }
    }

    @Test
    fun `announcement language files contain all connection states`() {
        val requiredKeys = setOf(
            "noir.prefix",
            "noir.announcement.no_client",
            "noir.announcement.sync_started",
            "noir.announcement.sync_success",
            "noir.announcement.sync_failed",
            "noir.announcement.version_mismatch"
            ,"noir.players.list"
            ,"noir.players.no_ysm_list"
            ,"noir.players.empty"
            ,"noir.players.pending"
            ,"noir.command.help.broadcast_opt_out"
            ,"noir.broadcast_reception.saved_enabled"
            ,"noir.broadcast_reception.saved_disabled"
            ,"noir.broadcast_reception.player_only"
            ,"noir.broadcast_reception.no_data"
            ,"noir.kickysm.usage"
            ,"noir.kickysm.not_found"
            ,"noir.kickysm.not_ysm"
            ,"noir.kickysm.success"
            ,"noir.kickysm.all_success"
            ,"noir.kickysm.none"
            ,"noir.kickysm.reason"
            ,"noir.database.switch_success"
            ,"noir.database.switch_disabled"
            ,"noir.database.switch_failed"
            ,"noir.identity.changed"
        )

        for (language in listOf("zh_CN", "en_US")) {
            val resource = javaClass.classLoader.getResourceAsStream("lang/$language.lang")
                ?: error("language file missing: $language")
            resource.bufferedReader(Charsets.UTF_8).use { reader ->
                val keys = reader.lineSequence()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { it.substringBefore('=') }
                    .toSet()
                assertTrue(keys.containsAll(requiredKeys), "Missing announcement key in $language")
            }
        }
    }

    @Test
    fun `plugin metadata declares players permission`() {
        val resource = javaClass.classLoader.getResourceAsStream("plugin.yml")
            ?: error("plugin.yml missing")
        resource.use {
            val metadata = yaml.load<Map<String, Any>>(it)
            val permissions = metadata["permissions"] as Map<*, *>
            assertTrue(permissions.containsKey("noir.command.players"))
            assertTrue(permissions.containsKey("noir.command.broadcast"))
            val broadcast = permissions["noir.command.broadcast"] as Map<*, *>
            assertEquals(true, broadcast["default"])
            assertTrue(permissions.containsKey("noir.command.kickysm"))
        }
    }

    @Test
    fun `plugin metadata declares mysql runtime libraries`() {
        val resource = javaClass.classLoader.getResourceAsStream("plugin.yml")
            ?: error("plugin.yml missing")
        resource.use {
            val metadata = yaml.load<Map<String, Any>>(it)
            val libraries = metadata["libraries"] as List<*>
            assertTrue(libraries.contains("com.zaxxer:HikariCP:6.3.0"))
            assertTrue(libraries.contains("com.mysql:mysql-connector-j:9.4.0"))
        }
    }

}

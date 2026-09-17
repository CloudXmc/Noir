package moe.meowrealms.noir.data.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import moe.meowrealms.noir.data.PlayerData

/** 只负责 MySQL 8 玩家数据访问，不接触 Bukkit 实时对象。 */
class MySqlPlayerDataRepository(
    private val settings: DatabaseSettings
) : AutoCloseable {
    private lateinit var dataSource: HikariDataSource

    fun initialize() {
        val hikariConfig = HikariConfig().apply {
            poolName = "Noir-MySQL"
            jdbcUrl = buildString {
                append("jdbc:mysql://")
                append(settings.host)
                append(':')
                append(settings.port)
                append('/')
                append(settings.databaseName)
                append("?useUnicode=true&characterEncoding=UTF-8")
                append("&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true")
            }
            username = settings.username
            password = settings.password
            driverClassName = "com.mysql.cj.jdbc.Driver"
            maximumPoolSize = settings.maximumPoolSize
            minimumIdle = settings.minimumIdle
            connectionTimeout = settings.connectionTimeoutMs
            validationTimeout = minOf(settings.connectionTimeoutMs, 5000L)
            initializationFailTimeout = settings.connectionTimeoutMs
        }

        val newDataSource = HikariDataSource(hikariConfig)
        try {
            newDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS `${settings.tableName}` (
                            `player_key` VARCHAR(80) NOT NULL,
                            `last_known_name` VARCHAR(64) NULL,
                            `data_json` LONGTEXT NOT NULL,
                            `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                ON UPDATE CURRENT_TIMESTAMP(3),
                            PRIMARY KEY (`player_key`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent()
                    )
                }
            }
            this.dataSource = newDataSource
        } catch (throwable: Throwable) {
            newDataSource.close()
            throw throwable
        }
    }

    fun load(playerKey: String): PlayerData? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT `data_json` FROM `${settings.tableName}` WHERE `player_key` = ?"
            ).use { statement ->
                statement.setString(1, playerKey)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) return null
                    return PlayerData.GSON.fromJson(resultSet.getString(1), PlayerData::class.java)
                }
            }
        }
    }

    fun save(playerKey: String, lastKnownName: String?, data: PlayerData) {
        val serialized = PlayerData.GSON.toJson(data)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO `${settings.tableName}` (`player_key`, `last_known_name`, `data_json`)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `last_known_name` = VALUES(`last_known_name`),
                    `data_json` = VALUES(`data_json`),
                    `updated_at` = CURRENT_TIMESTAMP(3)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, playerKey)
                statement.setString(2, lastKnownName)
                statement.setString(3, serialized)
                statement.executeUpdate()
            }
        }
    }

    fun testConnection() {
        dataSource.connection.use { connection ->
            check(connection.isValid(3)) { "MySQL 连接验证失败" }
        }
    }

    override fun close() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }
}

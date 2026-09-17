package moe.meowrealms.noir.data.storage

import org.bukkit.configuration.file.FileConfiguration

data class DatabaseSettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val databaseName: String,
    val username: String,
    val password: String,
    val tableName: String,
    val maximumPoolSize: Int,
    val minimumIdle: Int,
    val connectionTimeoutMs: Long
) {
    companion object {
        private val TABLE_NAME_PATTERN = Regex("[A-Za-z0-9_]+")

        fun from(configuration: FileConfiguration): DatabaseSettings {
            val host = configuration.getString("database.host", "127.0.0.1")!!.trim()
            val port = configuration.getInt("database.port", 3306)
            val databaseName = configuration.getString("database.name", "noir")!!.trim()
            val username = configuration.getString("database.username", "noir")!!.trim()
            val tableName = configuration.getString("database.table", "noir_player_data")!!.trim()
            val maximumPoolSize = configuration.getInt("database.pool.maximum-size", 8)
            val minimumIdle = configuration.getInt("database.pool.minimum-idle", 1)
            val connectionTimeoutMs = configuration.getLong("database.pool.connection-timeout-ms", 5000L)

            require(host.isNotEmpty()) { "database.host 不能为空" }
            require(port in 1..65535) { "database.port 必须在 1-65535 之间" }
            require(databaseName.isNotEmpty()) { "database.name 不能为空" }
            require(username.isNotEmpty()) { "database.username 不能为空" }
            require(TABLE_NAME_PATTERN.matches(tableName)) {
                "database.table 只能包含字母、数字和下划线"
            }
            require(maximumPoolSize in 1..32) {
                "database.pool.maximum-size 必须在 1-32 之间"
            }
            require(minimumIdle in 0..maximumPoolSize) {
                "database.pool.minimum-idle 必须在 0-maximum-size 之间"
            }
            require(connectionTimeoutMs in 1000L..60000L) {
                "database.pool.connection-timeout-ms 必须在 1000-60000 之间"
            }

            return DatabaseSettings(
                enabled = configuration.getBoolean("database.enabled", false),
                host = host,
                port = port,
                databaseName = databaseName,
                username = username,
                password = configuration.getString("database.password", "") ?: "",
                tableName = tableName,
                maximumPoolSize = maximumPoolSize,
                minimumIdle = minimumIdle,
                connectionTimeoutMs = connectionTimeoutMs
            )
        }
    }
}

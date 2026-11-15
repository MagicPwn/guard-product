package com.guard.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection

class Database private constructor(private val dataSource: HikariDataSource) : AutoCloseable {
    fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use(block)

    fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        }
    }

    fun initialize() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .baselineDescription("pre-Flyway Guard schema")
            .validateOnMigrate(true)
            .cleanDisabled(true)
            .load()
            .migrate()
    }

    fun healthy(): Boolean = try {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").use { it.next() }
            }
        }
    } catch (exception: Exception) {
        false
    }

    override fun close() = dataSource.close()

    companion object {
        fun open(url: String, user: String, password: String): Database {
            val config = HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = 10
                minimumIdle = 1
                connectionTimeout = 10_000
                validationTimeout = 3_000
                driverClassName = when {
                    url.startsWith("jdbc:h2:") -> "org.h2.Driver"
                    else -> "org.postgresql.Driver"
                }
            }
            return Database(HikariDataSource(config))
        }
    }
}

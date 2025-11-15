package org.ergoplatform.ergopay.paymentportal.config

import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy
import javax.sql.DataSource
import kotlin.concurrent.withLock

@Component
class EmbeddedPostgresManager(
    @Value("\${embedded.postgres.database:ergopay}") private val database: String,
    @Value("\${embedded.postgres.username:ergopay}") private val username: String,
    @Value("\${embedded.postgres.default-password:changeMe123}") private val defaultPassword: String,
    @Value("\${embedded.postgres.data-directory:data/postgres/db}") dataDirectoryConfig: String,
    @Value("\${embedded.postgres.credentials-file:data/postgres/credentials.properties}") credentialsFileConfig: String,
) {
    data class DbCredentials(val username: String, val password: String)

    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()
    private val dataDirectory: Path = Path.of(dataDirectoryConfig).toAbsolutePath()
    private val credentialsFile: Path = Path.of(credentialsFileConfig).toAbsolutePath()

    private lateinit var embeddedPostgres: EmbeddedPostgres
    private lateinit var hikariDataSource: HikariDataSource
    private var credentials: DbCredentials = DbCredentials(username, defaultPassword)

    @PostConstruct
    fun start() {
        lock.withLock {
            prepareFileSystem()
            credentials = loadCredentials() ?: DbCredentials(username, defaultPassword).also { persistCredentials(it) }

            embeddedPostgres = EmbeddedPostgres.builder()
                .setDataDirectory(dataDirectory)
                .setCleanDataDirectory(false)
                .setPort(0)
                .start()

            ensureDatabaseAndUser(credentials)
            hikariDataSource = buildDataSource(credentials)
            log.info("Embedded PostgreSQL started on port {}", embeddedPostgres.port)
        }
    }

    fun dataSource(): DataSource = hikariDataSource

    fun currentCredentials(): DbCredentials = credentials

    fun changePassword(newPassword: String) {
        val sanitized = newPassword.trim()
        require(sanitized.length >= 8) { "Password must be at least 8 characters long" }

        lock.withLock {
            ensureDatabaseAndUser(DbCredentials(credentials.username, sanitized))
            credentials = DbCredentials(credentials.username, sanitized)
            persistCredentials(credentials)

            hikariDataSource.password = sanitized
            hikariDataSource.hikariPoolMXBean?.softEvictConnections()
            log.info("Embedded PostgreSQL password updated")
        }
    }

    private fun ensureDatabaseAndUser(creds: DbCredentials) {
        val roleNameQuoted = "\"${creds.username.replace("\"", "\"\"")}\""
        val passwordLiteral = creds.password.replace("'", "''")
        val databaseQuoted = "\"${database.replace("\"", "\"\"")}\""

        embeddedPostgres.getPostgresDatabase().connection.use { connection ->
            val roleExists = connection.prepareStatement(
                "SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = ?"
            ).use { ps ->
                ps.setString(1, creds.username)
                ps.executeQuery().use { it.next() }
            }

            connection.createStatement().use { stmt ->
                if (!roleExists) {
                    stmt.execute("CREATE ROLE $roleNameQuoted LOGIN PASSWORD '$passwordLiteral'")
                } else {
                    stmt.execute("ALTER ROLE $roleNameQuoted WITH LOGIN PASSWORD '$passwordLiteral'")
                }
            }

            val dbExists = connection.prepareStatement(
                "SELECT 1 FROM pg_database WHERE datname = ?"
            ).use { ps ->
                ps.setString(1, database)
                ps.executeQuery().use { it.next() }
            }

            if (!dbExists) {
                connection.createStatement().use { stmt ->
                    stmt.execute("CREATE DATABASE $databaseQuoted OWNER $roleNameQuoted")
                }
            }
        }

        embeddedPostgres.getDatabase(creds.username, database).connection.use { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute("GRANT ALL PRIVILEGES ON SCHEMA public TO $roleNameQuoted")
            }
        }
    }
    private fun buildDataSource(creds: DbCredentials): HikariDataSource {
        val dataSource = HikariDataSource()
        dataSource.jdbcUrl = embeddedPostgres.getJdbcUrl(creds.username, database)
        dataSource.username = creds.username
        dataSource.password = creds.password
        dataSource.driverClassName = "org.postgresql.Driver"
        dataSource.maximumPoolSize = 5
        dataSource.minimumIdle = 1
        dataSource.isAutoCommit = true
        return dataSource
    }

    private fun prepareFileSystem() {
        if (!Files.exists(dataDirectory)) {
            Files.createDirectories(dataDirectory)
        }
        if (!Files.exists(credentialsFile.parent)) {
            Files.createDirectories(credentialsFile.parent)
        }
    }

    private fun loadCredentials(): DbCredentials? {
        if (!Files.exists(credentialsFile)) return null
        val props = Properties()
        Files.newInputStream(credentialsFile).use { props.load(it) }
        val user = props.getProperty("username")
        val password = props.getProperty("password")
        return if (user.isNullOrBlank() || password.isNullOrBlank()) null else DbCredentials(user, password)
    }

    private fun persistCredentials(creds: DbCredentials) {
        val props = Properties()
        props.setProperty("username", creds.username)
        props.setProperty("password", creds.password)
        Files.createDirectories(credentialsFile.parent)
        Files.newOutputStream(credentialsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use {
            props.store(it, "Embedded Postgres credentials")
        }
    }

    @PreDestroy
    fun stop() {
        lock.withLock {
            try {
                if (::hikariDataSource.isInitialized) {
                    hikariDataSource.close()
                }
                if (::embeddedPostgres.isInitialized) {
                    embeddedPostgres.close()
                }
            } catch (ex: IOException) {
                log.warn("Failed to cleanly stop embedded Postgres", ex)
            }
        }
    }
}

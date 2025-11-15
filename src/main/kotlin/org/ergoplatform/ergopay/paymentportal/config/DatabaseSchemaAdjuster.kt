package org.ergoplatform.ergopay.paymentportal.config

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.boot.context.event.ApplicationReadyEvent
import java.sql.ResultSet
import javax.sql.DataSource

@Component
class DatabaseSchemaAdjuster(
    private val dataSource: DataSource
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun ensureReducedTxColumnCapacity() {
        runCatching {
            dataSource.connection.use { connection ->
                val meta = connection.metaData.databaseProductName.lowercase()
                val statement = connection.createStatement()
                val rs: ResultSet = statement.executeQuery(
                    "SELECT data_type, character_maximum_length FROM information_schema.columns " +
                        "WHERE lower(table_name) = 'payment_request' AND lower(column_name) = 'reduced_tx'"
                )
                if (!rs.next()) {
                    return
                }
                val dataType = rs.getString("data_type")?.lowercase()
                val columnLength = rs.getLong("character_maximum_length")
                val needsAlter = when {
                    dataType == null -> false
                    dataType.contains("bytea") -> false
                    dataType.contains("blob") -> false
                    columnLength > 0 && columnLength >= 10000 -> false
                    else -> true
                }
                if (!needsAlter) {
                    return
                }

                val alterSql = when {
                    meta.contains("postgres") -> "ALTER TABLE payment_request ALTER COLUMN reduced_tx TYPE BYTEA USING lo_get(reduced_tx)"
                    meta.contains("h2") -> "ALTER TABLE payment_request ALTER COLUMN reduced_tx BLOB"
                    else -> null
                }
                alterSql?.let {
                    log.info("Adjusting reduced_tx column to support larger payloads using: {}", it)
                    connection.createStatement().use { alterStmt ->
                        alterStmt.execute(it)
                    }
                }
            }
        }.onFailure {
            log.warn("Failed to ensure reduced_tx column size", it)
        }
    }
}

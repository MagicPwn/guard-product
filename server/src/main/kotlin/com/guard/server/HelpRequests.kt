package com.guard.server

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

data class HelpRequestRow(
    val id: String,
    val deviceId: String,
    val parentId: String,
    val clientRequestId: String,
    val targetPackage: String,
    val triggerId: String,
    val reason: String,
    val message: String,
    val status: String,
    val parentReply: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val answeredAt: Instant?,
    val acknowledgedAt: Instant?
)

data class HelpRequestSave(val row: HelpRequestRow, val created: Boolean)

/**
 * Durable, idempotent child-to-parent help requests.
 *
 * <p>A request never grants time or changes enforcement. It only records what
 * the child saw on the task page and carries a parent's written reply back to
 * the same device. The client supplies an idempotency key so an offline outbox
 * can be retried safely after process death or a lost response.
 */
class HelpRequestRepository(private val database: Database) {
    fun create(deviceId: String, parentId: String, clientRequestId: String,
            targetPackage: String, triggerId: String, reason: String,
            message: String, ttlMinutes: Int = 24 * 60): HelpRequestSave =
        database.transaction { connection ->
            connection.prepareStatement("SELECT id FROM devices WHERE id = ? FOR UPDATE").use { statement ->
                statement.setString(1, deviceId)
                statement.executeQuery().use { rows -> if (!rows.next()) notFound("Device not found") }
            }
            expire(connection, deviceId)
            val existing = connection.prepareStatement(
                """SELECT * FROM help_requests
                   WHERE device_id = ? AND client_request_id = ? FOR UPDATE"""
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setString(2, clientRequestId)
                statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
            }
            if (existing != null) return@transaction HelpRequestSave(existing, false)

            val duplicate = connection.prepareStatement(
                """SELECT * FROM help_requests
                   WHERE device_id = ? AND reason = ? AND target_package = ?
                     AND message = ? AND created_at > ?
                   ORDER BY created_at DESC LIMIT 1"""
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setString(2, reason)
                statement.setString(3, targetPackage)
                statement.setString(4, message)
                statement.setTimestamp(5, Timestamp.from(Instant.now().minusSeconds(60)))
                statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
            }
            if (duplicate != null) return@transaction HelpRequestSave(duplicate, false)

            val now = Instant.now()
            val row = HelpRequestRow(
                id = "help-" + Codes.uuid(),
                deviceId = deviceId,
                parentId = parentId,
                clientRequestId = clientRequestId,
                targetPackage = targetPackage,
                triggerId = triggerId,
                reason = reason,
                message = message,
                status = "pending",
                parentReply = "",
                createdAt = now,
                expiresAt = now.plusSeconds(ttlMinutes * 60L),
                answeredAt = null,
                acknowledgedAt = null
            )
            connection.prepareStatement(
                """INSERT INTO help_requests
                   (id, device_id, parent_id, client_request_id, target_package, trigger_id,
                    reason, message, status, parent_reply, created_at, expires_at,
                    answered_at, acknowledged_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setString(1, row.id)
                statement.setString(2, row.deviceId)
                statement.setString(3, row.parentId)
                statement.setString(4, row.clientRequestId)
                statement.setString(5, row.targetPackage)
                statement.setString(6, row.triggerId)
                statement.setString(7, row.reason)
                statement.setString(8, row.message)
                statement.setString(9, row.status)
                statement.setString(10, row.parentReply)
                statement.setTimestamp(11, Timestamp.from(row.createdAt))
                statement.setTimestamp(12, Timestamp.from(row.expiresAt))
                statement.setTimestamp(13, null)
                statement.setTimestamp(14, null)
                statement.executeUpdate()
            }
            HelpRequestSave(row, true)
        }

    fun listForDevice(deviceId: String, limit: Int = 20): List<HelpRequestRow> =
        database.withConnection { connection ->
            expire(connection, deviceId)
            connection.prepareStatement(
                """SELECT * FROM help_requests WHERE device_id = ?
                   ORDER BY created_at DESC LIMIT ?"""
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setInt(2, limit.coerceIn(1, 100))
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(read(rows)) }
                }
            }
        }

    fun listForParent(parentId: String, deviceId: String, limit: Int = 50): List<HelpRequestRow> =
        database.withConnection { connection ->
            expire(connection, deviceId)
            connection.prepareStatement(
                """SELECT * FROM help_requests WHERE parent_id = ? AND device_id = ?
                   ORDER BY created_at DESC LIMIT ?"""
            ).use { statement ->
                statement.setString(1, parentId)
                statement.setString(2, deviceId)
                statement.setInt(3, limit.coerceIn(1, 200))
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(read(rows)) }
                }
            }
        }

    fun respond(deviceId: String, parentId: String, requestId: String,
            reply: String): HelpRequestRow? = database.transaction { connection ->
        val row = connection.prepareStatement(
            """SELECT * FROM help_requests
               WHERE id = ? AND device_id = ? AND parent_id = ? FOR UPDATE"""
        ).use { statement ->
            statement.setString(1, requestId)
            statement.setString(2, deviceId)
            statement.setString(3, parentId)
            statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
        } ?: return@transaction null
        if (row.status == "expired" || row.expiresAt.isBefore(Instant.now())) {
            markExpired(connection, row.id)
            return@transaction null
        }
        val now = Instant.now()
        connection.prepareStatement(
            """UPDATE help_requests SET status = 'answered', parent_reply = ?,
               answered_at = ?, acknowledged_at = NULL WHERE id = ?"""
        ).use { statement ->
            statement.setString(1, reply)
            statement.setTimestamp(2, Timestamp.from(now))
            statement.setString(3, row.id)
            statement.executeUpdate()
        }
        row.copy(status = "answered", parentReply = reply, answeredAt = now, acknowledgedAt = null)
    }

    fun acknowledge(deviceId: String, requestId: String): HelpRequestRow? =
        database.transaction { connection ->
            val row = connection.prepareStatement(
                "SELECT * FROM help_requests WHERE id = ? AND device_id = ? FOR UPDATE"
            ).use { statement ->
                statement.setString(1, requestId)
                statement.setString(2, deviceId)
                statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
            } ?: return@transaction null
            if (row.status != "answered") return@transaction row
            val now = Instant.now()
            connection.prepareStatement(
                "UPDATE help_requests SET status = 'acknowledged', acknowledged_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setString(2, row.id)
                statement.executeUpdate()
            }
            row.copy(status = "acknowledged", acknowledgedAt = now)
        }

    private fun expire(connection: Connection, deviceId: String) {
        connection.prepareStatement(
            """UPDATE help_requests SET status = 'expired'
               WHERE device_id = ? AND status IN ('pending', 'answered') AND expires_at <= ?"""
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.setTimestamp(2, Timestamp.from(Instant.now()))
            statement.executeUpdate()
        }
    }

    private fun markExpired(connection: Connection, id: String) {
        connection.prepareStatement(
            "UPDATE help_requests SET status = 'expired' WHERE id = ?"
        ).use { statement ->
            statement.setString(1, id)
            statement.executeUpdate()
        }
    }

    private fun read(rows: ResultSet) = HelpRequestRow(
        id = rows.getString("id"),
        deviceId = rows.getString("device_id"),
        parentId = rows.getString("parent_id"),
        clientRequestId = rows.getString("client_request_id"),
        targetPackage = rows.getString("target_package"),
        triggerId = rows.getString("trigger_id"),
        reason = rows.getString("reason"),
        message = rows.getString("message"),
        status = rows.getString("status"),
        parentReply = rows.getString("parent_reply"),
        createdAt = readInstant(rows, "created_at")!!,
        expiresAt = readInstant(rows, "expires_at")!!,
        answeredAt = readInstant(rows, "answered_at"),
        acknowledgedAt = readInstant(rows, "acknowledged_at")
    )
}

fun helpRequestView(row: HelpRequestRow) = HelpRequestView(
    row.id, row.deviceId, row.clientRequestId, row.targetPackage, row.triggerId,
    row.reason, row.message, row.status, row.parentReply, row.createdAt.toString(),
    row.expiresAt.toString(), row.answeredAt?.toString(), row.acknowledgedAt?.toString()
)

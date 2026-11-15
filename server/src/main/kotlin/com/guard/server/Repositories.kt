package com.guard.server

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime

data class ParentRow(val id: String, val email: String, val passwordHash: String)

data class ParentSessionRow(
    val id: String,
    val parentId: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val lastSeenAt: Instant
)

data class DeviceRow(
    val id: String,
    val deviceId: String,
    val publicKey: String,
    val name: String,
    val platform: String,
    val parentId: String?,
    val confirmed: Boolean,
    val revokedAt: Instant?,
    val createdAt: Instant,
    val lastSeenAt: Instant?
)

data class DeviceListRow(val device: DeviceRow, val latestRevision: Long)

data class PolicyRow(
    val revision: Long,
    val payload: String,
    val signature: String,
    val keyId: String,
    val createdAt: Instant
)

data class UsageRow(
    val day: String,
    val payload: String,
    val createdAt: Instant
)

data class AuditRow(
    val id: String,
    val actor: String,
    val event: String,
    val detail: String,
    val createdAt: Instant
)

data class CallRow(
    val id: String,
    val deviceId: String,
    val kind: String,
    val status: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val endedAt: Instant?,
    val roomRevokedAt: Instant?
)

data class DeviceAppRow(
    val packageName: String,
    val label: String,
    val category: Int,
    val enabled: Boolean,
    val updatedAt: Instant
)

data class GrantRow(
    val id: String,
    val deviceId: String,
    val packageName: String,
    val minutes: Int,
    val createdAt: Instant,
    val expiresAt: Instant,
    val appliedAt: Instant?,
    val appliedBy: String?,
    val revokedAt: Instant?,
    val revokedBy: String?
)

data class ScreenshotRow(
    val id: String,
    val deviceId: String,
    val callId: String,
    val contentType: String,
    val width: Int,
    val height: Int,
    val image: ByteArray,
    val createdAt: Instant,
    val expiresAt: Instant
)

class ParentRepository(private val database: Database) {
    fun create(email: String, passwordHash: String): ParentRow {
        val normalized = normalizeEmail(email)
        val id = Codes.uuid()
        val now = Instant.now()
        try {
            database.withConnection { connection ->
                connection.prepareStatement(
                    "INSERT INTO parents (id, email, password_hash, created_at) VALUES (?, ?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, id)
                    statement.setString(2, normalized)
                    statement.setString(3, passwordHash)
                    statement.setTimestamp(4, Timestamp.from(now))
                    statement.executeUpdate()
                }
            }
        } catch (exception: SQLException) {
            if (exception is SQLIntegrityConstraintViolationException || exception.sqlState == "23505") {
                conflict("An account with this email already exists")
            }
            throw exception
        }
        return ParentRow(id, normalized, passwordHash)
    }

    fun findByEmail(email: String): ParentRow? = database.withConnection { connection ->
        connection.prepareStatement(
            "SELECT id, email, password_hash FROM parents WHERE email = ?"
        ).use { statement ->
            statement.setString(1, normalizeEmail(email))
            statement.executeQuery().use { rows -> if (rows.next()) readParent(rows) else null }
        }
    }

    fun findById(id: String): ParentRow? = database.withConnection { connection ->
        connection.prepareStatement(
            "SELECT id, email, password_hash FROM parents WHERE id = ?"
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) readParent(rows) else null }
        }
    }

    private fun readParent(rows: ResultSet) = ParentRow(
        rows.getString("id"), rows.getString("email"), rows.getString("password_hash")
    )

    companion object {
        fun normalizeEmail(email: String): String {
            val normalized = email.trim().lowercase()
            if (normalized.length !in 3..254 || !normalized.contains('@')
                || normalized.startsWith('@') || normalized.endsWith('@')) {
                badRequest("A valid email address is required")
            }
            return normalized
        }
    }
}

class ParentSessionRepository(private val database: Database) {
    fun create(parentId: String, ttlSeconds: Long): ParentSessionRow {
        val id = Codes.uuid()
        val now = Instant.now()
        val expiresAt = now.plusSeconds(ttlSeconds)
        database.withConnection { connection ->
            connection.prepareStatement(
                """INSERT INTO parent_sessions
                   (id, parent_id, created_at, expires_at, last_seen_at)
                   VALUES (?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, parentId)
                statement.setTimestamp(3, Timestamp.from(now))
                statement.setTimestamp(4, Timestamp.from(expiresAt))
                statement.setTimestamp(5, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
        return ParentSessionRow(id, parentId, now, expiresAt, null, now)
    }

    fun list(parentId: String): List<ParentSessionRow> = database.withConnection { connection ->
        connection.prepareStatement(
            """SELECT id, parent_id, created_at, expires_at, revoked_at, last_seen_at
               FROM parent_sessions WHERE parent_id = ?
               ORDER BY created_at DESC LIMIT 50"""
        ).use { statement ->
            statement.setString(1, parentId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(readSession(rows))
                }
            }
        }
    }

    fun isActive(parentId: String, sessionId: String): Boolean = database.withConnection { connection ->
        val now = Instant.now()
        val active = connection.prepareStatement(
            """SELECT expires_at, revoked_at FROM parent_sessions
               WHERE id = ? AND parent_id = ?"""
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, parentId)
            statement.executeQuery().use { rows ->
                rows.next() && rows.getObject("revoked_at") == null &&
                    readInstant(rows, "expires_at")?.isAfter(now) == true
            }
        }
        if (active) {
            connection.prepareStatement(
                """UPDATE parent_sessions SET last_seen_at = ?
                   WHERE id = ? AND parent_id = ? AND last_seen_at < ?"""
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setString(2, sessionId)
                statement.setString(3, parentId)
                statement.setTimestamp(4, Timestamp.from(now.minusSeconds(60)))
                statement.executeUpdate()
            }
        }
        active
    }

    fun revoke(parentId: String, sessionId: String): ParentSessionRow? = database.transaction { connection ->
        val now = Instant.now()
        connection.prepareStatement(
            """UPDATE parent_sessions SET revoked_at = COALESCE(revoked_at, ?)
               WHERE id = ? AND parent_id = ?"""
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setString(2, sessionId)
            statement.setString(3, parentId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """SELECT id, parent_id, created_at, expires_at, revoked_at, last_seen_at
               FROM parent_sessions WHERE id = ? AND parent_id = ?"""
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, parentId)
            statement.executeQuery().use { rows ->
                if (rows.next()) readSession(rows) else null
            }
        }
    }

    fun revokeOthers(parentId: String, currentSessionId: String): Int = database.withConnection { connection ->
        connection.prepareStatement(
            """UPDATE parent_sessions SET revoked_at = ?
               WHERE parent_id = ? AND id <> ? AND revoked_at IS NULL"""
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(Instant.now()))
            statement.setString(2, parentId)
            statement.setString(3, currentSessionId)
            statement.executeUpdate()
        }
    }

    fun cleanupRetired(retentionSeconds: Long = 30L * 24 * 60 * 60): Int =
        database.withConnection { connection ->
            val cutoff = Timestamp.from(Instant.now().minusSeconds(retentionSeconds))
            connection.prepareStatement(
                """DELETE FROM parent_sessions
                   WHERE (revoked_at IS NOT NULL AND revoked_at < ?)
                      OR (revoked_at IS NULL AND expires_at < ?)"""
            ).use { statement ->
                statement.setTimestamp(1, cutoff)
                statement.setTimestamp(2, cutoff)
                statement.executeUpdate()
            }
        }

    private fun readSession(rows: ResultSet) = ParentSessionRow(
        rows.getString("id"),
        rows.getString("parent_id"),
        readInstant(rows, "created_at")!!,
        readInstant(rows, "expires_at")!!,
        readInstant(rows, "revoked_at"),
        readInstant(rows, "last_seen_at")!!
    )
}

class DeviceRepository(private val database: Database) {
    fun register(request: DeviceRegisterRequest, codeHash: String, expiresAt: Instant): DeviceRow {
        if (request.device_id.length !in 8..128 || !request.device_id.matches(Regex("^[A-Za-z0-9._:-]+$"))) {
            badRequest("device_id has an invalid format")
        }
        if (request.name.trim().length !in 1..80) badRequest("Device name must be 1 to 80 characters")
        if (request.public_key.length !in 80..2000) badRequest("Device public key is invalid")
        if (request.platform !in setOf("android", "ios", "web")) badRequest("Unsupported platform")
        return database.transaction { connection ->
            val existing = findByDeviceId(connection, request.device_id)
            if (existing != null) {
                if (existing.revokedAt != null) conflict("This device has been revoked")
                if (existing.parentId != null) conflict("This device is already paired")
                connection.prepareStatement(
                    """UPDATE devices SET public_key = ?, name = ?, platform = ?,
                       pairing_code_hash = ?, pairing_expires_at = ? WHERE id = ?"""
                ).use { statement ->
                    statement.setString(1, request.public_key)
                    statement.setString(2, request.name.trim())
                    statement.setString(3, request.platform)
                    statement.setString(4, codeHash)
                    statement.setTimestamp(5, Timestamp.from(expiresAt))
                    statement.setString(6, existing.id)
                    statement.executeUpdate()
                }
                return@transaction existing
            }
            val id = Codes.uuid()
            val now = Instant.now()
            connection.prepareStatement(
                """INSERT INTO devices (id, device_id, public_key, name, platform, pairing_code_hash,
                   pairing_expires_at, confirmed, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?)"""
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, request.device_id)
                statement.setString(3, request.public_key)
                statement.setString(4, request.name.trim())
                statement.setString(5, request.platform)
                statement.setString(6, codeHash)
                statement.setTimestamp(7, Timestamp.from(expiresAt))
                statement.setTimestamp(8, Timestamp.from(now))
                statement.executeUpdate()
            }
            DeviceRow(id, request.device_id, request.public_key, request.name.trim(),
                request.platform, null, false, null, now, null)
        }
    }

    fun findById(id: String): DeviceRow? = database.withConnection { findById(it, id) }
    fun findByDeviceId(deviceId: String): DeviceRow? = database.withConnection { findById(it, deviceId, true) }

    fun claim(codeHash: String, parentId: String): DeviceRow? = database.transaction { connection ->
        val device = connection.prepareStatement(
            """SELECT * FROM devices WHERE pairing_code_hash = ? AND parent_id IS NULL
               AND revoked_at IS NULL AND pairing_expires_at > ? FOR UPDATE"""
        ).use { statement ->
            statement.setString(1, codeHash)
            statement.setTimestamp(2, Timestamp.from(Instant.now()))
            statement.executeQuery().use { rows -> if (rows.next()) readDevice(rows) else null }
        } ?: return@transaction null
        connection.prepareStatement(
            """UPDATE devices SET parent_id = ?, pairing_code_hash = NULL,
               pairing_expires_at = NULL, confirmed = FALSE WHERE id = ?"""
        ).use { statement ->
            statement.setString(1, parentId)
            statement.setString(2, device.id)
            statement.executeUpdate()
        }
        device.copy(parentId = parentId)
    }

    fun confirm(deviceId: String, parentId: String): Boolean = database.withConnection { connection ->
        connection.prepareStatement(
            "UPDATE devices SET confirmed = TRUE WHERE id = ? AND parent_id = ? AND revoked_at IS NULL"
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.setString(2, parentId)
            statement.executeUpdate() > 0
        }
    }

    fun revoke(deviceId: String, parentId: String): Boolean = database.withConnection { connection ->
        connection.prepareStatement(
            "UPDATE devices SET revoked_at = ? WHERE id = ? AND parent_id = ? AND revoked_at IS NULL"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(Instant.now()))
            statement.setString(2, deviceId)
            statement.setString(3, parentId)
            statement.executeUpdate() > 0
        }
    }

    fun touch(deviceId: String) = database.withConnection { connection ->
        connection.prepareStatement("UPDATE devices SET last_seen_at = ? WHERE id = ?").use { statement ->
            statement.setTimestamp(1, Timestamp.from(Instant.now()))
            statement.setString(2, deviceId)
            statement.executeUpdate()
        }
        Unit
    }

    fun listByParent(parentId: String): List<DeviceListRow> = database.withConnection { connection ->
        connection.prepareStatement(
            """SELECT d.*, COALESCE((SELECT MAX(p.revision) FROM policy_revisions p
               WHERE p.device_id = d.id), 0) AS latest_revision FROM devices d
               WHERE d.parent_id = ? ORDER BY d.created_at DESC"""
        ).use { statement ->
            statement.setString(1, parentId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(DeviceListRow(readDevice(rows), rows.getLong("latest_revision")))
                    }
                }
            }
        }
    }

    fun parentEmail(deviceId: String): String? = database.withConnection { connection ->
        connection.prepareStatement(
            "SELECT p.email FROM parents p JOIN devices d ON d.parent_id = p.id WHERE d.id = ?"
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    private fun findByDeviceId(connection: Connection, deviceId: String): DeviceRow? =
        findById(connection, deviceId, byDeviceId = true)

    private fun findById(connection: Connection, key: String, byDeviceId: Boolean = false): DeviceRow? {
        return connection.prepareStatement(
            "SELECT * FROM devices WHERE ${if (byDeviceId) "device_id" else "id"} = ?"
        ).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rows -> if (rows.next()) readDevice(rows) else null }
        }
    }

    private fun readDevice(rows: ResultSet) = DeviceRow(
        id = rows.getString("id"),
        deviceId = rows.getString("device_id"),
        publicKey = rows.getString("public_key"),
        name = rows.getString("name"),
        platform = rows.getString("platform"),
        parentId = rows.getString("parent_id"),
        confirmed = rows.getBoolean("confirmed"),
        revokedAt = readInstant(rows, "revoked_at"),
        createdAt = readInstant(rows, "created_at")!!,
        lastSeenAt = readInstant(rows, "last_seen_at")
    )
}

fun readInstant(rows: ResultSet, column: String): Instant? {
    val value = rows.getObject(column) ?: return null
    return when (value) {
        is OffsetDateTime -> value.toInstant()
        is Timestamp -> value.toInstant()
        is java.time.LocalDateTime -> value.atZone(java.time.ZoneOffset.UTC).toInstant()
        is Instant -> value
        else -> null
    }
}

package com.guard.server

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

data class PreparedPolicy(val payload: String, val signature: String, val keyId: String)

class PolicyRepository(private val database: Database) {
    fun create(deviceId: String, prepare: (Long) -> PreparedPolicy): PolicyRow {
        return database.transaction { connection ->
            connection.prepareStatement("SELECT id FROM devices WHERE id = ? FOR UPDATE").use { statement ->
                statement.setString(1, deviceId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) notFound("Device not found")
                }
            }
            val next = connection.prepareStatement(
                "SELECT COALESCE(MAX(revision), 0) + 1 FROM policy_revisions WHERE device_id = ?"
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
            val createdAt = Instant.now()
            val prepared = prepare(next)
            connection.prepareStatement(
                """INSERT INTO policy_revisions (id, device_id, revision, payload, signature, key_id, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setString(1, Codes.uuid())
                statement.setString(2, deviceId)
                statement.setLong(3, next)
                statement.setString(4, prepared.payload)
                statement.setString(5, prepared.signature)
                statement.setString(6, prepared.keyId)
                statement.setTimestamp(7, Timestamp.from(createdAt))
                statement.executeUpdate()
            }
            PolicyRow(next, prepared.payload, prepared.signature, prepared.keyId, createdAt)
        }
    }

    fun latest(deviceId: String): PolicyRow? = database.withConnection { connection ->
        connection.prepareStatement(
            """SELECT revision, payload, signature, key_id, created_at FROM policy_revisions
               WHERE device_id = ? ORDER BY revision DESC LIMIT 1"""
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.executeQuery().use { rows -> if (rows.next()) readPolicy(rows) else null }
        }
    }

    fun latestAfter(deviceId: String, revision: Long): PolicyRow? = database.withConnection { connection ->
        connection.prepareStatement(
            """SELECT revision, payload, signature, key_id, created_at FROM policy_revisions
               WHERE device_id = ? AND revision > ? ORDER BY revision DESC LIMIT 1"""
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.setLong(2, revision)
            statement.executeQuery().use { rows -> if (rows.next()) readPolicy(rows) else null }
        }
    }

    fun history(deviceId: String, limit: Int = 50): List<PolicyRow> = database.withConnection { connection ->
        connection.prepareStatement(
            """SELECT revision, payload, signature, key_id, created_at FROM policy_revisions
               WHERE device_id = ? ORDER BY revision DESC LIMIT ?"""
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.setInt(2, limit.coerceIn(1, 100))
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(readPolicy(rows)) }
            }
        }
    }

    fun findRevision(deviceId: String, revision: Long): PolicyRow? =
        database.withConnection { connection ->
            connection.prepareStatement(
                """SELECT revision, payload, signature, key_id, created_at FROM policy_revisions
                   WHERE device_id = ? AND revision = ?"""
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setLong(2, revision)
                statement.executeQuery().use { rows -> if (rows.next()) readPolicy(rows) else null }
            }
        }

    private fun readPolicy(rows: java.sql.ResultSet) = PolicyRow(
        rows.getLong("revision"), rows.getString("payload"),
        rows.getString("signature"), rows.getString("key_id"),
        readInstant(rows, "created_at")!!
    )
}

class UsageRepository(private val database: Database) {
    fun save(deviceId: String, day: LocalDate, payload: String) = database.transaction { connection ->
        val exists = connection.prepareStatement(
            "SELECT id FROM usage_reports WHERE device_id = ? AND usage_date = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.setObject(2, day)
            statement.executeQuery().use { it.next() }
        }
        val now = Timestamp.from(Instant.now())
        if (exists) {
            connection.prepareStatement(
                "UPDATE usage_reports SET payload = ?, created_at = ? WHERE device_id = ? AND usage_date = ?"
            ).use { statement ->
                statement.setString(1, payload)
                statement.setTimestamp(2, now)
                statement.setString(3, deviceId)
                statement.setObject(4, day)
                statement.executeUpdate()
            }
        } else {
            connection.prepareStatement(
                "INSERT INTO usage_reports (id, device_id, usage_date, payload, created_at) VALUES (?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, Codes.uuid())
                statement.setString(2, deviceId)
                statement.setObject(3, day)
                statement.setString(4, payload)
                statement.setTimestamp(5, now)
                statement.executeUpdate()
            }
        }
        Unit
    }

    fun list(deviceId: String, from: LocalDate, to: LocalDate): List<UsageRow> =
        database.withConnection { connection ->
            connection.prepareStatement(
                """SELECT usage_date, payload, created_at FROM usage_reports
                   WHERE device_id = ? AND usage_date BETWEEN ? AND ? ORDER BY usage_date DESC"""
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setObject(2, from)
                statement.setObject(3, to)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(UsageRow(rows.getObject("usage_date", LocalDate::class.java).toString(),
                                rows.getString("payload"), readInstant(rows, "created_at")!!))
                        }
                    }
                }
            }
        }
}

class AuditRepository(private val database: Database) {
    fun append(deviceId: String, actor: String, event: String, detail: String) =
        database.withConnection { connection ->
            connection.prepareStatement(
                """INSERT INTO audit_events (id, device_id, actor, event, detail, created_at)
                   VALUES (?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setString(1, Codes.uuid())
                statement.setString(2, deviceId)
                statement.setString(3, actor)
                statement.setString(4, event)
                statement.setString(5, detail.take(2000))
                statement.setTimestamp(6, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
            Unit
        }

    fun list(deviceId: String, limit: Int = 100): List<AuditRow> =
        database.withConnection { connection ->
            connection.prepareStatement(
                """SELECT id, actor, event, detail, created_at FROM audit_events
                   WHERE device_id = ? ORDER BY created_at DESC LIMIT ?"""
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setInt(2, limit.coerceIn(1, 500))
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(AuditRow(rows.getString("id"), rows.getString("actor"),
                                rows.getString("event"), rows.getString("detail"),
                                readInstant(rows, "created_at")!!))
                        }
                    }
                }
            }
        }
}

class DeviceAppRepository(private val database: Database) {
    fun replace(deviceId: String, apps: List<DeviceAppPayload>) = database.transaction { connection ->
        connection.prepareStatement("DELETE FROM device_apps WHERE device_id = ?").use { statement ->
            statement.setString(1, deviceId)
            statement.executeUpdate()
        }
        val now = Timestamp.from(Instant.now())
        connection.prepareStatement(
            """INSERT INTO device_apps
               (device_id, package_name, label, category, enabled, updated_at)
               VALUES (?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            for (app in apps) {
                statement.setString(1, deviceId)
                statement.setString(2, app.packageName)
                statement.setString(3, app.label)
                statement.setInt(4, app.category)
                statement.setBoolean(5, app.enabled)
                statement.setTimestamp(6, now)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        Unit
    }

    fun list(deviceId: String): List<DeviceAppRow> = database.withConnection { connection ->
        connection.prepareStatement(
            """SELECT package_name, label, category, enabled, updated_at FROM device_apps
               WHERE device_id = ? ORDER BY label, package_name"""
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(DeviceAppRow(
                            rows.getString("package_name"),
                            rows.getString("label"),
                            rows.getInt("category"),
                            rows.getBoolean("enabled"),
                            readInstant(rows, "updated_at")!!
                        ))
                    }
                }
            }
        }
    }
}

data class GrantRevokeResult(val grant: GrantRow, val changed: Boolean)

class GrantRepository(private val database: Database) {
    fun create(deviceId: String, packageName: String, minutes: Int,
            expiresInMinutes: Int): GrantRow {
        val now = Instant.now()
        val row = GrantRow(
            Codes.uuid(), deviceId, packageName, minutes, now,
            now.plusSeconds(expiresInMinutes * 60L), null, null, null, null
        )
        database.withConnection { connection ->
            connection.prepareStatement(
                """INSERT INTO temporary_grants
                   (id, device_id, package_name, minutes, created_at, expires_at)
                   VALUES (?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setString(1, row.id)
                statement.setString(2, row.deviceId)
                statement.setString(3, row.packageName)
                statement.setInt(4, row.minutes)
                statement.setTimestamp(5, Timestamp.from(row.createdAt))
                statement.setTimestamp(6, Timestamp.from(row.expiresAt))
                statement.executeUpdate()
            }
        }
        return row
    }

    fun pendingFor(deviceId: String): List<GrantRow> = database.withConnection { connection ->
        connection.prepareStatement(
            """SELECT * FROM temporary_grants
               WHERE device_id = ? AND applied_at IS NULL AND revoked_at IS NULL
                 AND expires_at > ?
               ORDER BY created_at ASC"""
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.setTimestamp(2, Timestamp.from(Instant.now()))
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(readGrant(rows)) }
            }
        }
    }

    fun list(deviceId: String): List<GrantRow> = database.withConnection { connection ->
        connection.prepareStatement(
            "SELECT * FROM temporary_grants WHERE device_id = ? ORDER BY created_at DESC LIMIT 50"
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(readGrant(rows)) }
            }
        }
    }

    fun revokedSince(deviceId: String, since: Instant): List<GrantRow> =
        database.withConnection { connection ->
            connection.prepareStatement(
                """SELECT * FROM temporary_grants
                   WHERE device_id = ? AND revoked_at IS NOT NULL AND revoked_at >= ?
                   ORDER BY revoked_at DESC LIMIT 1000"""
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setTimestamp(2, Timestamp.from(since))
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(readGrant(rows)) }
                }
            }
        }

    fun markApplied(deviceId: String, grantId: String, actor: String): GrantRow? =
        database.transaction { connection ->
            val row = connection.prepareStatement(
                "SELECT * FROM temporary_grants WHERE id = ? AND device_id = ? FOR UPDATE"
            ).use { statement ->
                statement.setString(1, grantId)
                statement.setString(2, deviceId)
                statement.executeQuery().use { rows -> if (rows.next()) readGrant(rows) else null }
            } ?: return@transaction null
            if (row.appliedAt != null) return@transaction row
            if (row.revokedAt != null || !row.expiresAt.isAfter(Instant.now())) {
                return@transaction row
            }
            val appliedAt = Instant.now()
            connection.prepareStatement(
                "UPDATE temporary_grants SET applied_at = ?, applied_by = ? WHERE id = ?"
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(appliedAt))
                statement.setString(2, actor)
                statement.setString(3, grantId)
                statement.executeUpdate()
            }
            row.copy(appliedAt = appliedAt, appliedBy = actor)
        }

    fun revoke(deviceId: String, grantId: String, actor: String): GrantRevokeResult? =
        database.transaction { connection ->
            val row = connection.prepareStatement(
                "SELECT * FROM temporary_grants WHERE id = ? AND device_id = ? FOR UPDATE"
            ).use { statement ->
                statement.setString(1, grantId)
                statement.setString(2, deviceId)
                statement.executeQuery().use { rows -> if (rows.next()) readGrant(rows) else null }
            } ?: return@transaction null
            if (row.revokedAt != null) return@transaction GrantRevokeResult(row, false)
            val revokedAt = Instant.now()
            connection.prepareStatement(
                "UPDATE temporary_grants SET revoked_at = ?, revoked_by = ? WHERE id = ?"
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(revokedAt))
                statement.setString(2, actor)
                statement.setString(3, grantId)
                statement.executeUpdate()
            }
            GrantRevokeResult(row.copy(revokedAt = revokedAt, revokedBy = actor), true)
        }

    private fun readGrant(rows: java.sql.ResultSet) = GrantRow(
        rows.getString("id"), rows.getString("device_id"), rows.getString("package_name"),
        rows.getInt("minutes"), readInstant(rows, "created_at")!!,
        readInstant(rows, "expires_at")!!, readInstant(rows, "applied_at"),
        rows.getString("applied_by"), readInstant(rows, "revoked_at"),
        rows.getString("revoked_by")
    )
}
class ScreenshotRepository(private val database: Database) {
    fun save(deviceId: String, callId: String, image: ByteArray, width: Int,
            height: Int): ScreenshotRow {
        cleanupExpired()
        val now = Instant.now()
        val row = ScreenshotRow(
            Codes.uuid(), deviceId, callId, "image/jpeg", width, height, image,
            now, now.plusSeconds(15 * 60L)
        )
        database.withConnection { connection ->
            connection.prepareStatement(
                """INSERT INTO screenshots
                   (id, device_id, call_id, content_type, width, height, image, created_at, expires_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setString(1, row.id)
                statement.setString(2, row.deviceId)
                statement.setString(3, row.callId)
                statement.setString(4, row.contentType)
                statement.setInt(5, row.width)
                statement.setInt(6, row.height)
                statement.setBytes(7, row.image)
                statement.setTimestamp(8, Timestamp.from(row.createdAt))
                statement.setTimestamp(9, Timestamp.from(row.expiresAt))
                statement.executeUpdate()
            }
        }
        return row
    }

    fun list(deviceId: String): List<ScreenshotRow> {
        cleanupExpired()
        return database.withConnection { connection ->
            connection.prepareStatement(
                """SELECT * FROM screenshots WHERE device_id = ? AND expires_at > ?
                   ORDER BY created_at DESC LIMIT 20"""
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setTimestamp(2, Timestamp.from(Instant.now()))
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(readScreenshot(rows)) }
                }
            }
        }
    }

    fun find(id: String): ScreenshotRow? {
        cleanupExpired()
        return database.withConnection { connection ->
            connection.prepareStatement("SELECT * FROM screenshots WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rows ->
                    if (rows.next()) readScreenshot(rows) else null
                }
            }
        }
    }

    fun cleanupExpired(): Int = database.withConnection { connection ->
        connection.prepareStatement("DELETE FROM screenshots WHERE expires_at <= ?").use { statement ->
            statement.setTimestamp(1, Timestamp.from(Instant.now()))
            statement.executeUpdate()
        }
    }

    private fun readScreenshot(rows: java.sql.ResultSet) = ScreenshotRow(
        rows.getString("id"), rows.getString("device_id"), rows.getString("call_id"),
        rows.getString("content_type"), rows.getInt("width"), rows.getInt("height"),
        rows.getBytes("image"), readInstant(rows, "created_at")!!,
        readInstant(rows, "expires_at")!!
    )
}

class CallRepository(private val database: Database) {
    fun create(deviceId: String, kind: String, ttlSeconds: Long): CallRow {
        val now = Instant.now()
        val expires = now.plusSeconds(ttlSeconds)
        val row = CallRow(Codes.uuid(), deviceId, kind, "pending", now, expires, null, null)
        database.withConnection { connection ->
            connection.prepareStatement(
                """INSERT INTO call_requests (id, device_id, kind, status, created_at, expires_at)
                   VALUES (?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setString(1, row.id)
                statement.setString(2, deviceId)
                statement.setString(3, kind)
                statement.setString(4, row.status)
                statement.setTimestamp(5, Timestamp.from(now))
                statement.setTimestamp(6, Timestamp.from(expires))
                statement.executeUpdate()
            }
        }
        return row
    }

    fun pendingFor(deviceId: String): List<CallRow> {
        expireStale()
        return database.withConnection { connection ->
            connection.prepareStatement(
                """SELECT * FROM call_requests WHERE device_id = ? AND status = 'pending'
                   AND expires_at > ? ORDER BY created_at ASC"""
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setTimestamp(2, Timestamp.from(Instant.now()))
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(readCall(rows)) }
                }
            }
        }
    }

    fun respond(deviceId: String, callId: String, accept: Boolean): CallRow? {
        expireStale()
        return database.transaction { connection ->
            val call = connection.prepareStatement(
                "SELECT * FROM call_requests WHERE id = ? AND device_id = ? FOR UPDATE"
            ).use { statement ->
                statement.setString(1, callId)
                statement.setString(2, deviceId)
                statement.executeQuery().use { rows -> if (rows.next()) readCall(rows) else null }
            } ?: return@transaction null
            if (call.status != "pending" || call.expiresAt.isBefore(Instant.now())) return@transaction null
            val status = if (accept) "accepted" else "rejected"
            connection.prepareStatement(
                "UPDATE call_requests SET status = ?, responded_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, status)
                statement.setTimestamp(2, Timestamp.from(Instant.now()))
                statement.setString(3, callId)
                statement.executeUpdate()
            }
            call.copy(status = status)
        }
    }

    fun end(callId: String): CallRow? = database.transaction { connection ->
        val call = connection.prepareStatement(
            "SELECT * FROM call_requests WHERE id = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, callId)
            statement.executeQuery().use { rows -> if (rows.next()) readCall(rows) else null }
        } ?: return@transaction null
        if (call.status == "ended") return@transaction call
        if (call.status != "accepted") return@transaction null
        val endedAt = Instant.now()
        connection.prepareStatement(
            "UPDATE call_requests SET status = 'ended', ended_at = ? WHERE id = ?"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(endedAt))
            statement.setString(2, callId)
            statement.executeUpdate()
        }
        call.copy(status = "ended", endedAt = endedAt)
    }

    fun markRoomRevoked(callId: String): CallRow? = database.transaction { connection ->
        val call = connection.prepareStatement(
            "SELECT * FROM call_requests WHERE id = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, callId)
            statement.executeQuery().use { rows -> if (rows.next()) readCall(rows) else null }
        } ?: return@transaction null
        if (call.status != "ended") return@transaction null
        if (call.roomRevokedAt != null) return@transaction call
        val revokedAt = Instant.now()
        connection.prepareStatement(
            "UPDATE call_requests SET room_revoked_at = ? WHERE id = ?"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(revokedAt))
            statement.setString(2, callId)
            statement.executeUpdate()
        }
        call.copy(roomRevokedAt = revokedAt)
    }

    fun listForDevice(deviceId: String): List<CallRow> {
        expireStale()
        return database.withConnection { connection ->
            connection.prepareStatement(
                "SELECT * FROM call_requests WHERE device_id = ? ORDER BY created_at DESC LIMIT 20"
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(readCall(rows)) }
                }
            }
        }
    }

    fun find(callId: String): CallRow? {
        expireStale()
        return database.withConnection { connection ->
            connection.prepareStatement("SELECT * FROM call_requests WHERE id = ?").use { statement ->
                statement.setString(1, callId)
                statement.executeQuery().use { rows -> if (rows.next()) readCall(rows) else null }
            }
        }
    }

    fun expireStale(): Int = database.withConnection { connection ->
        connection.prepareStatement(
            "UPDATE call_requests SET status = 'expired' WHERE status = 'pending' AND expires_at <= ?"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(Instant.now()))
            statement.executeUpdate()
        }
    }

    private fun readCall(rows: java.sql.ResultSet) = CallRow(
        rows.getString("id"), rows.getString("device_id"), rows.getString("kind"),
        rows.getString("status"), readInstant(rows, "created_at")!!,
        readInstant(rows, "expires_at")!!, readInstant(rows, "ended_at"),
        readInstant(rows, "room_revoked_at")
    )
}

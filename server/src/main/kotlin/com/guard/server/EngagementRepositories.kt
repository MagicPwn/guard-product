package com.guard.server

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

data class QuizResultRow(
    val id: String,
    val deviceId: String,
    val attemptId: String,
    val occurredAt: Instant,
    val targetPackage: String,
    val triggerId: String?,
    val policyRevision: Long,
    val quizVersion: Long,
    val questionCount: Int,
    val correctCount: Int,
    val rewardGranted: Boolean,
    val questions: String,
    val receivedAt: Instant
)

data class QuizResultSave(val row: QuizResultRow, val inserted: Boolean)

class QuizResultRepository(private val database: Database) {
    fun save(deviceId: String, attemptId: String, occurredAt: Instant,
            targetPackage: String, triggerId: String?, policyRevision: Long,
            quizVersion: Long, questionCount: Int, correctCount: Int,
            rewardGranted: Boolean, questions: String): QuizResultSave =
        database.transaction { connection ->
            connection.prepareStatement("SELECT id FROM devices WHERE id = ? FOR UPDATE").use { statement ->
                statement.setString(1, deviceId)
                statement.executeQuery().use { rows -> if (!rows.next()) notFound("Device not found") }
            }
            val existing = connection.prepareStatement(
                "SELECT * FROM quiz_attempt_results WHERE device_id = ? AND attempt_id = ?"
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setString(2, attemptId)
                statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
            }
            if (existing != null) return@transaction QuizResultSave(existing, false)

            val receivedAt = Instant.now()
            val row = QuizResultRow(
                Codes.uuid(), deviceId, attemptId, occurredAt, targetPackage, triggerId,
                policyRevision, quizVersion, questionCount, correctCount,
                rewardGranted, questions, receivedAt
            )
            connection.prepareStatement(
                """INSERT INTO quiz_attempt_results
                   (id, device_id, attempt_id, occurred_at, target_package, trigger_id,
                    policy_revision, quiz_version, question_count, correct_count,
                    reward_granted, questions, received_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setString(1, row.id)
                statement.setString(2, row.deviceId)
                statement.setString(3, row.attemptId)
                statement.setTimestamp(4, Timestamp.from(row.occurredAt))
                statement.setString(5, row.targetPackage)
                statement.setString(6, row.triggerId)
                statement.setLong(7, row.policyRevision)
                statement.setLong(8, row.quizVersion)
                statement.setInt(9, row.questionCount)
                statement.setInt(10, row.correctCount)
                statement.setBoolean(11, row.rewardGranted)
                statement.setString(12, row.questions)
                statement.setTimestamp(13, Timestamp.from(row.receivedAt))
                statement.executeUpdate()
            }
            QuizResultSave(row, true)
        }

    fun list(deviceId: String, limit: Int): List<QuizResultRow> = database.withConnection { connection ->
        connection.prepareStatement(
            """SELECT * FROM quiz_attempt_results WHERE device_id = ?
               ORDER BY occurred_at DESC LIMIT ?"""
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.setInt(2, limit.coerceIn(1, 100))
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(read(rows)) }
            }
        }
    }

    fun listBetween(deviceId: String, from: Instant, to: Instant,
            limit: Int = 5_000): List<QuizResultRow> = database.withConnection { connection ->
        connection.prepareStatement(
            """SELECT * FROM quiz_attempt_results
               WHERE device_id = ? AND occurred_at >= ? AND occurred_at < ?
               ORDER BY occurred_at ASC LIMIT ?"""
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.setTimestamp(2, Timestamp.from(from))
            statement.setTimestamp(3, Timestamp.from(to))
            statement.setInt(4, limit.coerceIn(1, 10_000))
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(read(rows)) }
            }
        }
    }

    private fun read(rows: ResultSet) = QuizResultRow(
        rows.getString("id"), rows.getString("device_id"), rows.getString("attempt_id"),
        readInstant(rows, "occurred_at")!!, rows.getString("target_package"),
        rows.getString("trigger_id"), rows.getLong("policy_revision"),
        rows.getLong("quiz_version"), rows.getInt("question_count"),
        rows.getInt("correct_count"), rows.getBoolean("reward_granted"),
        rows.getString("questions"), readInstant(rows, "received_at")!!
    )
}

data class NoticeRow(
    val id: String,
    val deviceId: String,
    val kind: String,
    val message: String,
    val status: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val acknowledgedAt: Instant?
)

class NoticeRepository(private val database: Database) {
    fun create(deviceId: String, kind: String, message: String): NoticeRow {
        val now = Instant.now()
        val row = NoticeRow(Codes.uuid(), deviceId, kind, message, "pending", now,
            now.plusSeconds(24 * 60 * 60L), null)
        database.withConnection { connection ->
            connection.prepareStatement(
                """INSERT INTO device_notices
                   (id, device_id, kind, message, status, created_at, expires_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setString(1, row.id)
                statement.setString(2, row.deviceId)
                statement.setString(3, row.kind)
                statement.setString(4, row.message)
                statement.setString(5, row.status)
                statement.setTimestamp(6, Timestamp.from(row.createdAt))
                statement.setTimestamp(7, Timestamp.from(row.expiresAt))
                statement.executeUpdate()
            }
        }
        return row
    }

    fun pendingFor(deviceId: String): List<NoticeRow> {
        expireStale()
        return database.withConnection { connection ->
            connection.prepareStatement(
                """SELECT * FROM device_notices
                   WHERE device_id = ? AND status = 'pending' AND expires_at > ?
                   ORDER BY created_at ASC"""
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setTimestamp(2, Timestamp.from(Instant.now()))
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(read(rows)) }
                }
            }
        }
    }

    fun list(deviceId: String): List<NoticeRow> {
        expireStale()
        return database.withConnection { connection ->
            connection.prepareStatement(
                "SELECT * FROM device_notices WHERE device_id = ? ORDER BY created_at DESC LIMIT 50"
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(read(rows)) }
                }
            }
        }
    }

    fun acknowledge(deviceId: String, noticeId: String): NoticeRow? = database.transaction { connection ->
        val row = connection.prepareStatement(
            "SELECT * FROM device_notices WHERE id = ? AND device_id = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, noticeId)
            statement.setString(2, deviceId)
            statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
        } ?: return@transaction null
        if (row.status != "pending" || row.expiresAt.isBefore(Instant.now())) return@transaction null
        val now = Instant.now()
        connection.prepareStatement(
            "UPDATE device_notices SET status = 'acknowledged', acknowledged_at = ? WHERE id = ?"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setString(2, noticeId)
            statement.executeUpdate()
        }
        row.copy(status = "acknowledged", acknowledgedAt = now)
    }

    private fun expireStale() = database.withConnection { connection ->
        connection.prepareStatement(
            "UPDATE device_notices SET status = 'expired' WHERE status = 'pending' AND expires_at <= ?"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(Instant.now()))
            statement.executeUpdate()
        }
        Unit
    }

    private fun read(rows: ResultSet) = NoticeRow(
        rows.getString("id"), rows.getString("device_id"), rows.getString("kind"),
        rows.getString("message"), rows.getString("status"),
        readInstant(rows, "created_at")!!, readInstant(rows, "expires_at")!!,
        readInstant(rows, "acknowledged_at")
    )
}

data class ManualQuizTriggerRow(
    val id: String,
    val deviceId: String,
    val parentId: String,
    val targetPackage: String,
    val status: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val deliveredAt: Instant?,
    val finishedAt: Instant?,
    val detail: String
)

/**
 * Parent-initiated quiz requests.
 *
 * <p>The child device still re-checks the signed policy and the local quiz
 * limits before showing a prompt. This repository only tracks delivery and the
 * parent-visible outcome; it never carries an authorization that can bypass
 * those checks.
 */
class ManualQuizTriggerRepository(private val database: Database) {
    private val activeStatuses = "'pending','accepted'"
    private val reusableStatuses = "'pending'"

    fun create(deviceId: String, parentId: String, targetPackage: String,
            ttlMinutes: Int): ManualQuizTriggerRow = database.transaction { connection ->
        connection.prepareStatement("SELECT id FROM devices WHERE id = ? FOR UPDATE").use { statement ->
            statement.setString(1, deviceId)
            statement.executeQuery().use { rows -> if (!rows.next()) notFound("Device not found") }
        }
        expire(connection)
        val existing = connection.prepareStatement(
            """SELECT * FROM manual_quiz_triggers
               WHERE device_id = ? AND parent_id = ? AND target_package = ?
                 AND status IN ($reusableStatuses)
                 AND expires_at > ?
               ORDER BY created_at DESC LIMIT 1"""
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.setString(2, parentId)
            statement.setString(3, targetPackage)
            statement.setTimestamp(4, Timestamp.from(Instant.now()))
            statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
        }
        if (existing != null) return@transaction existing

        val now = Instant.now()
        val row = ManualQuizTriggerRow(
            "quiz-manual-" + digestHex("SHA-256",
                Codes.uuid().toByteArray(Charsets.UTF_8)).take(24),
            deviceId, parentId, targetPackage, "pending", now,
            now.plusSeconds(ttlMinutes * 60L), null, null, ""
        )
        connection.prepareStatement(
            """INSERT INTO manual_quiz_triggers
               (id, device_id, parent_id, target_package, status, created_at, expires_at,
                delivered_at, finished_at, detail)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setString(1, row.id)
            statement.setString(2, row.deviceId)
            statement.setString(3, row.parentId)
            statement.setString(4, row.targetPackage)
            statement.setString(5, row.status)
            statement.setTimestamp(6, Timestamp.from(row.createdAt))
            statement.setTimestamp(7, Timestamp.from(row.expiresAt))
            statement.setNull(8, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            statement.setNull(9, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            statement.setString(10, row.detail)
            statement.executeUpdate()
        }
        row
    }

    fun pendingFor(deviceId: String): List<ManualQuizTriggerRow> {
        expireStale()
        return database.withConnection { connection ->
            connection.prepareStatement(
                """SELECT * FROM manual_quiz_triggers
                   WHERE device_id = ? AND status = 'pending' AND expires_at > ?
                     AND parent_id = (SELECT parent_id FROM devices WHERE id = ?)
                   ORDER BY created_at ASC LIMIT 20"""
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setTimestamp(2, Timestamp.from(Instant.now()))
                statement.setString(3, deviceId)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(read(rows)) }
                }
            }
        }
    }

    fun list(deviceId: String, parentId: String): List<ManualQuizTriggerRow> {
        expireStale()
        return database.withConnection { connection ->
            connection.prepareStatement(
                """SELECT * FROM manual_quiz_triggers WHERE device_id = ? AND parent_id = ?
                   ORDER BY created_at DESC LIMIT 50"""
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.setString(2, parentId)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(read(rows)) }
                }
            }
        }
    }

    fun acknowledge(deviceId: String, triggerId: String, status: String,
            detail: String): ManualQuizTriggerRow? = database.transaction { connection ->
        val row = connection.prepareStatement(
            """SELECT * FROM manual_quiz_triggers
               WHERE id = ? AND device_id = ? FOR UPDATE"""
        ).use { statement ->
            statement.setString(1, triggerId)
            statement.setString(2, deviceId)
            statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
        } ?: return@transaction null

        val now = Instant.now()
        if (row.expiresAt.isBefore(now) && row.status in setOf("pending", "accepted")) {
            update(connection, triggerId, "expired", "", row.deliveredAt, now)
            return@transaction row.copy(status = "expired", finishedAt = now)
        }
        if (row.status == status) return@transaction row
        val allowed = when (row.status) {
            "pending" -> setOf("accepted", "rejected", "completed")
            "accepted" -> setOf("rejected", "completed")
            else -> emptySet()
        }
        if (status !in allowed) return@transaction null
        val deliveredAt = if (status == "accepted") now else row.deliveredAt
        val finishedAt = if (status == "accepted") null else now
        update(connection, triggerId, status, detail, deliveredAt, finishedAt)
        row.copy(status = status, detail = detail, deliveredAt = deliveredAt,
            finishedAt = finishedAt)
    }

    /** Called when a signed quiz result arrives; safe to repeat. */
    fun complete(deviceId: String, triggerId: String): ManualQuizTriggerRow? =
        database.transaction { connection ->
            val row = connection.prepareStatement(
                """SELECT * FROM manual_quiz_triggers
                   WHERE id = ? AND device_id = ? FOR UPDATE"""
            ).use { statement ->
                statement.setString(1, triggerId)
                statement.setString(2, deviceId)
                statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
            } ?: return@transaction null
            if (row.status == "completed") return@transaction row
            if (row.status !in setOf("pending", "accepted")) return@transaction null
            val now = Instant.now()
            update(connection, triggerId, "completed", "", row.deliveredAt, now)
            row.copy(status = "completed", finishedAt = now)
        }

    private fun update(connection: java.sql.Connection, triggerId: String, status: String,
            detail: String, deliveredAt: Instant?, finishedAt: Instant?) {
        connection.prepareStatement(
            "UPDATE manual_quiz_triggers SET status = ?, detail = ?, delivered_at = ?, "
                + "finished_at = ? WHERE id = ?"
        ).use { statement ->
            statement.setString(1, status)
            statement.setString(2, detail)
            if (deliveredAt == null) statement.setNull(3, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            else statement.setTimestamp(3, Timestamp.from(deliveredAt))
            if (finishedAt == null) statement.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            else statement.setTimestamp(4, Timestamp.from(finishedAt))
            statement.setString(5, triggerId)
            statement.executeUpdate()
        }
    }

    private fun expireStale() = database.withConnection { connection -> expire(connection) }

    private fun expire(connection: java.sql.Connection) {
        val now = Timestamp.from(Instant.now())
        connection.prepareStatement(
            """UPDATE manual_quiz_triggers SET status = 'expired', finished_at = ?
               WHERE status IN ($activeStatuses) AND expires_at <= ?"""
        ).use { statement ->
            statement.setTimestamp(1, now)
            statement.setTimestamp(2, now)
            statement.executeUpdate()
        }
    }

    private fun read(rows: ResultSet) = ManualQuizTriggerRow(
        rows.getString("id"), rows.getString("device_id"), rows.getString("parent_id"),
        rows.getString("target_package"), rows.getString("status"),
        readInstant(rows, "created_at")!!, readInstant(rows, "expires_at")!!,
        readInstant(rows, "delivered_at"), readInstant(rows, "finished_at"),
        rows.getString("detail") ?: ""
    )
}

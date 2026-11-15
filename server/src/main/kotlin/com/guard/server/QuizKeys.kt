package com.guard.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.Signature
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64

@Serializable
data class QuizKeyRotateRequest(val transition: String)

@Serializable
data class QuizKeyTransitionView(
    val id: String,
    @kotlinx.serialization.SerialName("old_key_id") val oldKeyId: String,
    @kotlinx.serialization.SerialName("new_key_id") val newKeyId: String,
    @kotlinx.serialization.SerialName("new_public_key") val newPublicKey: String,
    val transition: String,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String,
    @kotlinx.serialization.SerialName("revoked_at") val revokedAt: String?
)

data class QuizKeyTransitionRow(
    val id: String,
    val parentId: String,
    val oldKeyId: String,
    val newKeyId: String,
    val newPublicKey: String,
    val transition: String,
    val createdAt: Instant,
    val revokedAt: Instant?
)

data class VerifiedQuizKeyTransition(
    val oldKeyId: String,
    val newKeyId: String,
    val newPublicKey: String,
    val transition: String
)

object QuizKeyTransitionValidator {
    private const val MAX_TRANSITION_CHARS = 8_000
    private const val MAX_TIME_SKEW_MILLIS = 60L * 60L * 1000L

    fun verify(parentId: String, transitionText: String,
            knownPublicKey: (String) -> String?): VerifiedQuizKeyTransition {
        if (transitionText.isEmpty() || transitionText.length > MAX_TRANSITION_CHARS) {
            badRequest("Key transition is missing or too large")
        }
        val objectValue = try {
            parseTransitionObject(transitionText)
        } catch (exception: Exception) {
            badRequest("Key transition is not valid JSON")
        }
        if (objectValue.transitionLong("schema") != 1L) badRequest("Unsupported key transition schema")
        if (objectValue.transitionString("type") != "quiz-key-transition") {
            badRequest("Key transition type is not supported")
        }
        val oldKeyId = objectValue.transitionString("old_key_id").lowercase()
        val newKeyId = objectValue.transitionString("new_key_id").lowercase()
        val newPublicKey = objectValue.transitionString("new_public_key").trim()
        val signatureText = objectValue.transitionString("signature")
        val notBefore = objectValue.transitionLong("not_before")
        if (oldKeyId.isEmpty() || newKeyId.isEmpty() || oldKeyId == newKeyId) {
            badRequest("Key transition ids are invalid")
        }
        if (Math.abs(System.currentTimeMillis() - notBefore) > MAX_TIME_SKEW_MILLIS) {
            badRequest("Key transition is outside the accepted time window")
        }
        val newKey = QuizPackValidator.inspectPublicKey(newPublicKey)
        if (!newKey.keyId.equals(newKeyId, ignoreCase = true)) {
            badRequest("Key transition new_key_id does not match the public key")
        }
        val oldPublicKey = knownPublicKey(oldKeyId)
            ?: badRequest("The old signing key is not known to this account")
        val message = transitionMessage(oldKeyId, newKeyId, newKey.normalizedBase64, notBefore)
        val signature = try {
            Base64.getDecoder().decode(signatureText)
        } catch (exception: IllegalArgumentException) {
            badRequest("Key transition signature is not valid base64")
        }
        val valid = try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(QuizPackValidator.inspectPublicKey(oldPublicKey).publicKey)
            verifier.update(message.toByteArray(Charsets.UTF_8))
            verifier.verify(signature)
        } catch (exception: Exception) {
            badRequest("Key transition signature is malformed")
        }
        if (!valid) badRequest("Key transition signature does not match the old key")
        return VerifiedQuizKeyTransition(oldKeyId, newKey.keyId,
            newKey.normalizedBase64, transitionText)
    }

    fun transitionMessage(oldKeyId: String, newKeyId: String, newPublicKey: String,
            notBefore: Long): String =
        "guard-quiz-key-transition-v1\n$oldKeyId\n$newKeyId\n$newPublicKey\n$notBefore"
}

/** Small, strict JSON accessor used only for key transitions. */
private val transitionJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }

private fun parseTransitionObject(value: String): JsonObject =
    transitionJson.parseToJsonElement(value) as? JsonObject ?: error("not an object")

private fun JsonObject.transitionString(name: String): String {
    val value = this[name] as? JsonPrimitive
    if (value == null || !value.isString) badRequest("$name must be a string")
    return value.content
}

private fun JsonObject.transitionLong(name: String): Long {
    val value = (this[name] as? JsonPrimitive)?.longOrNull
        ?: badRequest("$name must be an integer")
    return value
}

class QuizKeyTransitionRepository(private val database: Database) {
    fun knownPublicKey(parentId: String, keyId: String): String? =
        database.withConnection { connection ->
            connection.prepareStatement(
                "SELECT public_key FROM quiz_packs WHERE parent_id = ? AND key_id = ? LIMIT 1"
            ).use { statement ->
                statement.setString(1, parentId)
                statement.setString(2, keyId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            } ?: connection.prepareStatement(
                """SELECT new_public_key FROM quiz_key_transitions
                   WHERE parent_id = ? AND new_key_id = ? AND revoked_at IS NULL LIMIT 1"""
            ).use { statement ->
                statement.setString(1, parentId)
                statement.setString(2, keyId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }

    fun create(parentId: String, verified: VerifiedQuizKeyTransition): QuizKeyTransitionRow =
        database.transaction { connection ->
            val existing = connection.prepareStatement(
                "SELECT * FROM quiz_key_transitions WHERE parent_id = ? AND new_key_id = ?"
            ).use { statement ->
                statement.setString(1, parentId)
                statement.setString(2, verified.newKeyId)
                statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
            }
            if (existing != null) return@transaction existing
            val now = Instant.now()
            val id = "quiz-key-transition-" + Codes.uuid()
            connection.prepareStatement(
                """INSERT INTO quiz_key_transitions
                   (id, parent_id, old_key_id, new_key_id, new_public_key, transition,
                    created_at, revoked_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, parentId)
                statement.setString(3, verified.oldKeyId)
                statement.setString(4, verified.newKeyId)
                statement.setString(5, verified.newPublicKey)
                statement.setString(6, verified.transition)
                statement.setTimestamp(7, Timestamp.from(now))
                statement.setTimestamp(8, null)
                statement.executeUpdate()
            }
            QuizKeyTransitionRow(id, parentId, verified.oldKeyId, verified.newKeyId,
                verified.newPublicKey, verified.transition, now, null)
        }

    fun list(parentId: String): List<QuizKeyTransitionRow> = database.withConnection { connection ->
        connection.prepareStatement(
            "SELECT * FROM quiz_key_transitions WHERE parent_id = ? ORDER BY created_at ASC"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(read(rows)) }
            }
        }
    }

    fun revoke(parentId: String, id: String): QuizKeyTransitionRow? = database.transaction { connection ->
        val row = connection.prepareStatement(
            "SELECT * FROM quiz_key_transitions WHERE parent_id = ? AND id = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.setString(2, id)
            statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
        } ?: return@transaction null
        if (row.revokedAt != null) return@transaction row
        val activeKeyId = connection.prepareStatement(
            "SELECT key_id FROM quiz_packs WHERE parent_id = ? AND active = TRUE"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        if (activeKeyId != null && activeKeyId.equals(row.newKeyId, ignoreCase = true)) {
            conflict("Cannot revoke a quiz key that signs the active quiz pack")
        }
        val now = Instant.now()
        connection.prepareStatement(
            "UPDATE quiz_key_transitions SET revoked_at = ? WHERE id = ?"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setString(2, id)
            statement.executeUpdate()
        }
        row.copy(revokedAt = now)
    }

    private fun read(rows: ResultSet) = QuizKeyTransitionRow(
        rows.getString("id"), rows.getString("parent_id"), rows.getString("old_key_id"),
        rows.getString("new_key_id"), rows.getString("new_public_key"),
        rows.getString("transition"), readInstant(rows, "created_at")!!,
        readInstant(rows, "revoked_at")
    )
}

fun quizKeyTransitionView(row: QuizKeyTransitionRow) = QuizKeyTransitionView(
    row.id, row.oldKeyId, row.newKeyId, row.newPublicKey, row.transition,
    row.createdAt.toString(), row.revokedAt?.toString()
)

package com.guard.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.Timestamp
import java.text.Normalizer
import java.time.Instant
import java.util.Base64
import java.util.Locale

@Serializable
data class QuizPackCreateRequest(
    val title: String,
    val public_key: String,
    val envelope: String,
    val delta_envelope: String? = null,
    val base_version: Long? = null,
    val activate: Boolean = true
)

@Serializable
data class QuizPackSummary(
    val id: String,
    val title: String,
    val version: Long,
    val key_id: String,
    val question_count: Int,
    @kotlinx.serialization.SerialName("base_version") val baseVersion: Long?,
    @kotlinx.serialization.SerialName("has_delta") val hasDelta: Boolean,
    val published_at: String,
    val active: Boolean,
    val created_at: String
)

@Serializable
data class QuizPackDetail(
    val id: String,
    val title: String,
    val version: Long,
    val key_id: String,
    val public_key: String,
    val envelope: String,
    val question_count: Int,
    @kotlinx.serialization.SerialName("base_version") val baseVersion: Long?,
    @kotlinx.serialization.SerialName("delta_envelope") val deltaEnvelope: String?,
    val published_at: String,
    val active: Boolean,
    val created_at: String
)

@Serializable
data class DeviceQuizPackView(
    val version: Long,
    val key_id: String,
    val public_key: String,
    val envelope: String,
    val published_at: String,
    @kotlinx.serialization.SerialName("delta_envelope") val deltaEnvelope: String? = null,
    @kotlinx.serialization.SerialName("base_version") val baseVersion: Long? = null
)

data class QuizPackRow(
    val id: String,
    val parentId: String,
    val title: String,
    val version: Long,
    val keyId: String,
    val publicKey: String,
    val envelope: String,
    val questionCount: Int,
    val deltaEnvelope: String?,
    val deltaBaseVersion: Long?,
    val publishedAt: String,
    val active: Boolean,
    val createdAt: Instant
)

data class VerifiedQuizPack(
    val version: Long,
    val keyId: String,
    val normalizedPublicKey: String,
    val publishedAt: String,
    val questionCount: Int,
    val envelope: String
)

data class VerifiedQuizDelta(
    val version: Long,
    val baseVersion: Long,
    val keyId: String,
    val normalizedPublicKey: String,
    val publishedAt: String,
    val questionCount: Int,
    val envelope: String
)

private data class PayloadValidation(
    val version: Long,
    val publishedAt: String,
    val questionCount: Int
)

data class QuizPublicKeyInfo(
    val publicKey: PublicKey,
    val normalizedBase64: String,
    val keyId: String
)

object QuizPackValidator {
    private const val SCHEMA = 1
    private const val MAX_ENVELOPE_CHARS = 8_000_000
    private const val MAX_PAYLOAD_BYTES = 6_000_000
    private const val MAX_QUESTIONS = 500
    private const val MAX_QUESTION_IMAGE_BYTES = 512 * 1024
    private const val MAX_PACK_IMAGE_BYTES = 5 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = false }
    private val whitespace = Regex("\\s+")
    private val language = Regex("[a-z]{2,8}(?:-[a-z0-9]{1,8})*")
    private val pngMagic = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )
    private val commonKeys = setOf(
        "id", "prompt", "type", "subject", "grade", "difficulty", "language", "tags",
        "image_base64", "image_mime"
    )
    private val allowedByType = mapOf(
        "single_choice" to (commonKeys + setOf("options", "answer_index")),
        "multiple_choice" to (commonKeys + setOf("options", "answer_indices")),
        "true_false" to (commonKeys + setOf("answer")),
        "short_answer" to (commonKeys + setOf("accepted_answers"))
    )

    fun verify(publicKeyBase64: String, envelopeText: String): VerifiedQuizPack {
        if (envelopeText.isEmpty() || envelopeText.length > MAX_ENVELOPE_CHARS) {
            badRequest("Quiz envelope is missing or too large")
        }
        val keyInfo = inspectPublicKey(publicKeyBase64)
        val publicKey = keyInfo.publicKey
        val normalizedPublicKey = keyInfo.normalizedBase64
        val envelope = parseObject(envelopeText, "Quiz envelope")
        if (envelope.requiredLong("schema") != SCHEMA.toLong()) {
            badRequest("Unsupported quiz envelope schema")
        }
        val keyId = envelope.requiredString("key_id")
        val payload = envelope.requiredString("payload")
        val signatureText = envelope.requiredString("signature")
        if (payload.toByteArray(Charsets.UTF_8).size !in 1..MAX_PAYLOAD_BYTES) {
            badRequest("Quiz payload is missing or too large")
        }
        val expectedKeyId = keyInfo.keyId
        if (!expectedKeyId.equals(keyId, ignoreCase = true)) {
            badRequest("Quiz pack was signed by an untrusted key")
        }
        val signature = try {
            Base64.getDecoder().decode(signatureText)
        } catch (exception: IllegalArgumentException) {
            badRequest("Quiz signature is not valid base64")
        }
        val signatureMatches = try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(payload.toByteArray(Charsets.UTF_8))
            verifier.verify(signature)
        } catch (exception: java.security.SignatureException) {
            badRequest("Quiz signature is malformed")
        }
        if (!signatureMatches) badRequest("Quiz signature does not match the payload")

        val validation = validatePayload(parseObject(payload, "Quiz payload"))
        return VerifiedQuizPack(validation.version, expectedKeyId, normalizedPublicKey,
            validation.publishedAt, validation.questionCount, envelopeText)
    }

    /** Returns the signed payload schema without verifying its signature. */
    fun payloadSchema(envelopeText: String): Long {
        if (envelopeText.isEmpty() || envelopeText.length > MAX_ENVELOPE_CHARS) {
            badRequest("Quiz envelope is missing or too large")
        }
        val envelope = parseObject(envelopeText, "Quiz envelope")
        if (envelope.requiredLong("schema") != SCHEMA.toLong()) {
            badRequest("Unsupported quiz envelope schema")
        }
        val payload = parseObject(envelope.requiredString("payload"), "Quiz payload")
        return payload.requiredLong("schema")
    }

    /**
     * Verifies a signed schema-2 delta against the exact signed full pack it
     * names. The merged result is validated with the same question and image
     * rules as a full pack before the server will host it.
     */
    fun verifyDelta(publicKeyBase64: String, deltaEnvelopeText: String,
            baseEnvelopeText: String, targetEnvelopeText: String,
            targetVersion: Long): VerifiedQuizDelta {
        if (deltaEnvelopeText.isEmpty() || deltaEnvelopeText.length > MAX_ENVELOPE_CHARS) {
            badRequest("Quiz delta envelope is missing or too large")
        }
        val keyInfo = inspectPublicKey(publicKeyBase64)
        val envelope = parseObject(deltaEnvelopeText, "Quiz delta envelope")
        if (envelope.requiredLong("schema") != SCHEMA.toLong()) {
            badRequest("Unsupported quiz delta envelope schema")
        }
        val keyId = envelope.requiredString("key_id")
        if (!keyInfo.keyId.equals(keyId, ignoreCase = true)) {
            badRequest("Quiz delta was signed by an untrusted key")
        }
        val payload = envelope.requiredString("payload")
        val signatureText = envelope.requiredString("signature")
        if (payload.toByteArray(Charsets.UTF_8).size !in 1..MAX_PAYLOAD_BYTES) {
            badRequest("Quiz delta payload is missing or too large")
        }
        val signature = try {
            Base64.getDecoder().decode(signatureText)
        } catch (exception: IllegalArgumentException) {
            badRequest("Quiz delta signature is not valid base64")
        }
        val signatureMatches = try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(keyInfo.publicKey)
            verifier.update(payload.toByteArray(Charsets.UTF_8))
            verifier.verify(signature)
        } catch (exception: java.security.SignatureException) {
            badRequest("Quiz delta signature is malformed")
        }
        if (!signatureMatches) badRequest("Quiz delta signature does not match the payload")

        val delta = parseObject(payload, "Quiz delta payload")
        if (delta.requiredLong("schema") != 2L) {
            badRequest("Unsupported quiz delta payload schema")
        }
        val version = delta.requiredLong("version")
        val baseVersion = delta.requiredLong("base_version")
        if (version != targetVersion) badRequest("Quiz delta target version does not match the full pack")
        if (baseVersion <= 0 || version <= baseVersion) {
            badRequest("Quiz delta versions are out of order")
        }
        val publishedAt = delta.requiredString("published_at")
        optionalText(publishedAt, "Quiz delta published_at", 64)
        if (publishedAt.isBlank()) badRequest("Quiz delta published_at must not be empty")

        val basePack = verify(publicKeyBase64, baseEnvelopeText)
        if (basePack.version != baseVersion) {
            badRequest("Quiz delta base_version does not match the base envelope")
        }
        val basePayload = parseObject(parseObject(baseEnvelopeText, "Quiz base envelope")
            .requiredString("payload"), "Quiz base payload")
        val baseQuestions = basePayload["questions"] as? JsonArray
            ?: badRequest("Quiz base payload questions must be an array")
        val questionsById = LinkedHashMap<String, JsonObject>()
        baseQuestions.forEach { element ->
            val question = element as? JsonObject
                ?: badRequest("Quiz base question must be an object")
            questionsById[question.requiredString("id").trim()] = question
        }
        val operations = delta["operations"] as? JsonArray
            ?: badRequest("Quiz delta operations must be an array")
        if (operations.size !in 1..MAX_QUESTIONS) {
            badRequest("Quiz delta needs 1 to $MAX_QUESTIONS operations")
        }
        operations.forEach { value ->
            val operation = value as? JsonObject ?: badRequest("Quiz delta operation must be an object")
            when (operation.requiredString("op")) {
                "put" -> {
                    val question = operation["question"] as? JsonObject
                        ?: badRequest("Quiz delta put needs a question object")
                    questionsById[question.requiredString("id").trim()] = question
                }
                "remove" -> {
                    val id = operation.requiredString("id").trim()
                    if (questionsById.remove(id) == null) {
                        badRequest("Quiz delta removes a question missing from the base pack")
                    }
                }
                else -> badRequest("Quiz delta operation is not supported")
            }
        }
        if (questionsById.size !in 1..MAX_QUESTIONS) {
            badRequest("Quiz delta result needs 1 to $MAX_QUESTIONS questions")
        }
        val merged = JsonObject(mapOf(
            "schema" to JsonPrimitive(SCHEMA),
            "version" to JsonPrimitive(version),
            "published_at" to JsonPrimitive(publishedAt),
            "questions" to JsonArray(questionsById.values.toList())
        ))
        val validation = validatePayload(merged)
        if (validation.version != version) badRequest("Quiz delta result version is inconsistent")
        val target = verify(publicKeyBase64, targetEnvelopeText)
        if (target.version != version) {
            badRequest("Quiz delta target version does not match the full pack")
        }
        if (target.publishedAt != publishedAt) {
            badRequest("Quiz delta published_at does not match the full target pack")
        }
        val targetPayload = parseObject(parseObject(targetEnvelopeText, "Quiz target envelope")
            .requiredString("payload"), "Quiz target payload")
        val targetQuestions = targetPayload["questions"] as? JsonArray
            ?: badRequest("Quiz target payload questions must be an array")
        val targetById = LinkedHashMap<String, JsonObject>()
        targetQuestions.forEach { element ->
            val question = element as? JsonObject
                ?: badRequest("Quiz target question must be an object")
            targetById[question.requiredString("id").trim()] = question
        }
        if (targetById != questionsById) {
            badRequest("Quiz delta result does not match the full target pack")
        }
        return VerifiedQuizDelta(version, baseVersion, keyInfo.keyId,
            keyInfo.normalizedBase64, publishedAt, validation.questionCount, deltaEnvelopeText)
    }

    private fun validatePayload(payloadObject: JsonObject): PayloadValidation {
        if (payloadObject.requiredLong("schema") != SCHEMA.toLong()) {
            badRequest("Unsupported quiz payload schema")
        }
        val version = payloadObject.requiredLong("version")
        if (version !in 1..9_999_999_999L) badRequest("Quiz pack version is out of range")
        val publishedAt = payloadObject.requiredString("published_at")
        optionalText(publishedAt, "Quiz published_at", 64)
        if (publishedAt.isBlank()) badRequest("Quiz published_at must not be empty")
        val questions = payloadObject["questions"] as? JsonArray
            ?: badRequest("Quiz payload questions must be an array")
        if (questions.size !in 1..MAX_QUESTIONS) {
            badRequest("Quiz pack needs 1 to $MAX_QUESTIONS questions")
        }
        val ids = HashSet<String>()
        var imageBytes = 0
        questions.forEachIndexed { index, element ->
            val question = element as? JsonObject
                ?: badRequest("Question ${index + 1} must be an object")
            imageBytes += validateQuestion(question, index)
            if (imageBytes > MAX_PACK_IMAGE_BYTES) badRequest("Embedded quiz images exceed 5 MiB in total")
            val id = question.requiredString("id").trim()
            if (!ids.add(id)) badRequest("Duplicate question id: $id")
        }
        return PayloadValidation(version, publishedAt, questions.size)
    }

    fun inspectPublicKey(publicKeyBase64: String): QuizPublicKeyInfo {
        if (publicKeyBase64.length !in 80..2_000) badRequest("Quiz public key has an invalid length")
        val key = parsePublicKey(publicKeyBase64)
        return QuizPublicKeyInfo(
            key,
            Base64.getEncoder().encodeToString(key.encoded),
            digestHex("SHA-256", key.encoded)
        )
    }

    private fun validateQuestion(question: JsonObject, index: Int): Int {
        val prefix = "Question ${index + 1}"
        val type = question.optionalString("type", "single_choice").trim().lowercase(Locale.ROOT)
        val allowed = allowedByType[type] ?: badRequest("$prefix has an unsupported question type")
        val unknown = question.keys - allowed
        if (unknown.isNotEmpty()) badRequest("$prefix has an unknown field: ${unknown.first()}")
        text(question.requiredString("id"), "$prefix id", 128)
        text(question.requiredString("prompt"), "$prefix prompt", 2_000)
        validateMetadata(question, prefix)
        when (type) {
            "single_choice" -> {
                val options = options(question, prefix)
                val answer = question.requiredInt("answer_index")
                if (answer !in options.indices) badRequest("$prefix answer index is out of range")
            }
            "multiple_choice" -> {
                val options = options(question, prefix)
                val answers = (question["answer_indices"] as? JsonArray)
                    ?: badRequest("$prefix answer_indices must be an array")
                if (answers.size < 2 || answers.size >= options.size) {
                    badRequest("$prefix needs 2 or more, but not all, marked correct")
                }
                val seen = HashSet<Int>()
                answers.forEach { value ->
                    val answer = (value as? JsonPrimitive)?.intOrNull
                        ?: badRequest("$prefix answer indices must be integers")
                    if (answer !in options.indices || !seen.add(answer)) {
                        badRequest("$prefix answer indices are out of range or duplicated")
                    }
                }
            }
            "true_false" -> {
                if ((question["answer"] as? JsonPrimitive)?.booleanOrNull == null) {
                    badRequest("$prefix answer must be true or false")
                }
            }
            "short_answer" -> {
                val answers = question["accepted_answers"] as? JsonArray
                    ?: badRequest("$prefix accepted_answers must be an array")
                if (answers.isEmpty() || answers.size > 8) {
                    badRequest("$prefix needs 1 to 8 accepted answers")
                }
                val normalized = HashSet<String>()
                answers.forEach { value ->
                    val answer = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: badRequest("$prefix accepted answers must be strings")
                    text(answer, "$prefix accepted answer", 300)
                    if (!normalized.add(normalizedText(answer))) {
                        badRequest("$prefix accepted answers must be distinct after normalization")
                    }
                }
            }
        }
        return validateImage(question, prefix)
    }

    private fun validateMetadata(question: JsonObject, prefix: String) {
        optionalText(question.optionalString("subject", ""), "$prefix subject", 64)
        val grade = question.optionalInt("grade", 0)
        if (grade !in 0..12) badRequest("$prefix grade must be between 0 and 12")
        val difficulty = question.optionalString("difficulty", "").trim().lowercase(Locale.ROOT)
        if (difficulty.isNotEmpty() && difficulty !in setOf("easy", "medium", "hard")) {
            badRequest("$prefix difficulty must be easy, medium, or hard")
        }
        val languageTag = question.optionalString("language", "").trim().lowercase(Locale.ROOT)
        if (languageTag.isNotEmpty() && !language.matches(languageTag)) {
            badRequest("$prefix language must be a BCP-47 tag")
        }
        val tags = question["tags"]
        if (tags != null) {
            val array = tags as? JsonArray ?: badRequest("$prefix tags must be an array")
            if (array.size > 32) badRequest("$prefix supports at most 32 tags")
            array.forEach { value ->
                val tag = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: badRequest("$prefix tags must be strings")
                optionalText(tag, "$prefix tag", 48)
            }
        }
    }

    private fun options(question: JsonObject, prefix: String): List<String> {
        val array = question["options"] as? JsonArray
            ?: badRequest("$prefix options must be an array")
        if (array.size !in 2..8) badRequest("$prefix needs 2 to 8 options")
        val values = array.map { value ->
            val option = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: badRequest("$prefix options must be strings")
            text(option, "$prefix option", 300)
            option.trim()
        }
        if (values.toSet().size != values.size) badRequest("$prefix options must be distinct")
        return values
    }

    private fun validateImage(question: JsonObject, prefix: String): Int {
        val image = question.optionalString("image_base64", "").trim()
        val mime = question.optionalString("image_mime", "").trim().lowercase(Locale.ROOT)
        if (image.isEmpty() != mime.isEmpty()) {
            badRequest("$prefix image_base64 and image_mime must be supplied together")
        }
        if (image.isEmpty()) return 0
        val data = try {
            Base64.getDecoder().decode(image)
        } catch (exception: IllegalArgumentException) {
            badRequest("$prefix image is not valid base64")
        }
        if (data.size !in 4..MAX_QUESTION_IMAGE_BYTES) {
            badRequest("$prefix image must be 4 bytes to 512 KiB")
        }
        val jpeg = data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
                && data[2] == 0xFF.toByte()
        val png = data.size >= pngMagic.size && pngMagic.indices.all { data[it] == pngMagic[it] }
        if ((jpeg && mime != "image/jpeg") || (png && mime != "image/png") || (!jpeg && !png)) {
            badRequest("$prefix image must be JPEG or PNG with a matching MIME type")
        }
        return data.size
    }

    private fun parsePublicKey(base64: String): PublicKey {
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim()))
            )
        } catch (exception: Exception) {
            badRequest("Quiz public key is not a valid EC public key")
        }
        if (key !is ECPublicKey) badRequest("Quiz public key must be an EC key")
        return key
    }

    private fun parseObject(text: String, label: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject ?: badRequest("$label must be a JSON object")
    } catch (exception: SerializationException) {
        badRequest("$label is not valid JSON")
    }

    private fun JsonObject.requiredString(name: String): String {
        val value = this[name] as? JsonPrimitive
        if (value == null || !value.isString) badRequest("$name must be a string")
        return value.content
    }

    private fun JsonObject.optionalString(name: String, fallback: String): String {
        val value = this[name] ?: return fallback
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) badRequest("$name must be a string")
        return primitive.content
    }

    private fun JsonObject.requiredLong(name: String): Long =
        (this[name] as? JsonPrimitive)?.longOrNull ?: badRequest("$name must be an integer")

    private fun JsonObject.requiredInt(name: String): Int =
        (this[name] as? JsonPrimitive)?.intOrNull ?: badRequest("$name must be an integer")

    private fun JsonObject.optionalInt(name: String, fallback: Int): Int {
        val value = this[name] ?: return fallback
        return (value as? JsonPrimitive)?.intOrNull ?: badRequest("$name must be an integer")
    }

    private fun text(value: String, label: String, maxLength: Int) {
        val cleaned = value.trim()
        if (cleaned.isEmpty() || cleaned.length > maxLength) {
            badRequest("$label must be 1 to $maxLength characters")
        }
    }

    private fun optionalText(value: String, label: String, maxLength: Int) {
        val cleaned = value.trim()
        if (cleaned.length > maxLength || cleaned.any { Character.isISOControl(it) }) {
            badRequest("$label is invalid or too long")
        }
    }

    private fun normalizedText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
            .replace(whitespace, " ").lowercase(Locale.ROOT)
}

class QuizPackRepository(private val database: Database) {
    fun create(
        parentId: String,
        title: String,
        verified: VerifiedQuizPack,
        delta: VerifiedQuizDelta?,
        activate: Boolean
    ): QuizPackRow {
        val id = Codes.uuid()
        val now = Instant.now()
        try {
            database.transaction { connection ->
                if (activate) {
                    requireNewer(connection, parentId, verified.version)
                }
                connection.prepareStatement(
                    """INSERT INTO quiz_packs
                        (id, parent_id, title, version, key_id, public_key, envelope,
                         delta_envelope, delta_base_version, question_count,
                         published_at, active, created_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
                ).use { statement ->
                    statement.setString(1, id)
                    statement.setString(2, parentId)
                    statement.setString(3, title)
                    statement.setLong(4, verified.version)
                    statement.setString(5, verified.keyId)
                    statement.setString(6, verified.normalizedPublicKey)
                    statement.setString(7, verified.envelope)
                    statement.setString(8, delta?.envelope)
                    if (delta == null) statement.setNull(9, java.sql.Types.BIGINT)
                    else statement.setLong(9, delta.baseVersion)
                    statement.setInt(10, verified.questionCount)
                    statement.setString(11, verified.publishedAt)
                    statement.setBoolean(12, activate)
                    statement.setTimestamp(13, Timestamp.from(now))
                    statement.executeUpdate()
                }
                if (activate) {
                    connection.prepareStatement(
                        "UPDATE quiz_packs SET active = FALSE WHERE parent_id = ? AND id <> ?"
                    ).use { statement ->
                        statement.setString(1, parentId)
                        statement.setString(2, id)
                        statement.executeUpdate()
                    }
                }
            }
        } catch (exception: java.sql.SQLException) {
            if (exception is SQLIntegrityConstraintViolationException || exception.sqlState == "23505") {
                conflict("A quiz pack with this version already exists")
            }
            throw exception
        }
        return find(parentId, id) ?: error("Quiz pack was not persisted")
    }

    fun list(parentId: String): List<QuizPackRow> = database.withConnection { connection ->
        connection.prepareStatement(
            "SELECT * FROM quiz_packs WHERE parent_id = ? ORDER BY version DESC LIMIT 100"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(read(rows)) }
            }
        }
    }

    fun find(parentId: String, id: String): QuizPackRow? = database.withConnection { connection ->
        connection.prepareStatement("SELECT * FROM quiz_packs WHERE parent_id = ? AND id = ?")
            .use { statement ->
                statement.setString(1, parentId)
                statement.setString(2, id)
                statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
            }
    }

    fun findActive(parentId: String): QuizPackRow? = database.withConnection { connection ->
        connection.prepareStatement(
            "SELECT * FROM quiz_packs WHERE parent_id = ? AND active = TRUE"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
        }
    }

    fun hasVersion(parentId: String, version: Long): Boolean = database.withConnection { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM quiz_packs WHERE parent_id = ? AND version = ?"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.setLong(2, version)
            statement.executeQuery().use { rows -> rows.next() }
        }
    }

    fun findVersion(parentId: String, version: Long): QuizPackRow? = database.withConnection { connection ->
        connection.prepareStatement(
            "SELECT * FROM quiz_packs WHERE parent_id = ? AND version = ?"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.setLong(2, version)
            statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
        }
    }

    fun activate(parentId: String, id: String): QuizPackRow? = database.transaction { connection ->
        val target = connection.prepareStatement(
            "SELECT * FROM quiz_packs WHERE parent_id = ? AND id = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.setString(2, id)
            statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
        } ?: return@transaction null
        requireNewer(connection, parentId, target.version)
        connection.prepareStatement(
            "UPDATE quiz_packs SET active = FALSE WHERE parent_id = ?"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE quiz_packs SET active = TRUE WHERE parent_id = ? AND id = ?"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.setString(2, id)
            statement.executeUpdate()
        }
        target.copy(active = true)
    }

    private fun requireNewer(connection: Connection, parentId: String, version: Long) {
        val current = connection.prepareStatement(
            "SELECT version FROM quiz_packs WHERE parent_id = ? AND active = TRUE"
        ).use { statement ->
            statement.setString(1, parentId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }
        if (current != null && version <= current) {
            conflict("Quiz pack version $version must be newer than active version $current")
        }
    }

    private fun read(rows: ResultSet) = QuizPackRow(
        id = rows.getString("id"),
        parentId = rows.getString("parent_id"),
        title = rows.getString("title"),
        version = rows.getLong("version"),
        keyId = rows.getString("key_id"),
        publicKey = rows.getString("public_key"),
        envelope = rows.getString("envelope"),
        deltaEnvelope = rows.getString("delta_envelope"),
        deltaBaseVersion = rows.getObject("delta_base_version")?.let { rows.getLong("delta_base_version") },
        questionCount = rows.getInt("question_count"),
        publishedAt = rows.getString("published_at"),
        active = rows.getBoolean("active"),
        createdAt = readInstant(rows, "created_at")!!
    )
}

fun quizSummary(row: QuizPackRow) = QuizPackSummary(
    row.id, row.title, row.version, row.keyId, row.questionCount,
    row.deltaBaseVersion, row.deltaEnvelope != null,
    row.publishedAt, row.active, row.createdAt.toString()
)

fun quizDetail(row: QuizPackRow) = QuizPackDetail(
    row.id, row.title, row.version, row.keyId, row.publicKey, row.envelope,
    row.questionCount, row.deltaBaseVersion, row.deltaEnvelope,
    row.publishedAt, row.active, row.createdAt.toString()
)

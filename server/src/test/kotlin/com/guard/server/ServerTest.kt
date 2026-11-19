package com.guard.server

import com.auth0.jwt.JWT
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class RecordingLiveKitRooms : LiveKitRoomService {
        val deletedRooms = mutableListOf<String>()
        var failDeletion = false

        override fun deleteRoom(roomName: String) {
            if (failDeletion) throw LiveKitRoomDeletionException("simulated LiveKit failure")
            deletedRooms += roomName
        }
    }

    private fun services(liveKitRoomService: LiveKitRoomService? = null): Services {
        val name = "guard-" + java.util.UUID.randomUUID()
        val database = Database.open(
            "jdbc:h2:mem:$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "sa", ""
        )
        database.initialize()
        val config = ServerConfig(
            port = 0,
            host = "127.0.0.1",
            databaseUrl = "jdbc:h2:mem:$name",
            databaseUser = "sa",
            databasePassword = "",
            jwtSecret = "test-secret-".repeat(4),
            policySigner = PolicySigner.generate(),
            livekitUrl = "wss://livekit.example.test",
            livekitApiKey = "test-api-key",
            livekitApiSecret = "test-api-secret",
            corsOrigins = listOf("http://localhost:5173"),
            devMode = true
        )
        return Services(config, database, liveKitRoomService = liveKitRoomService)
    }

    private suspend fun postJson(
        client: io.ktor.client.HttpClient,
        path: String,
        body: String,
        token: String? = null,
        expected: HttpStatusCode = HttpStatusCode.OK
    ): String {
        val response = client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(expected, response.status, response.bodyAsText())
        return response.bodyAsText()
    }

    private suspend fun putJson(
        client: io.ktor.client.HttpClient,
        path: String,
        body: String,
        token: String,
        expected: HttpStatusCode = HttpStatusCode.OK
    ): String {
        val response = client.put(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(expected, response.status, response.bodyAsText())
        return response.bodyAsText()
    }

    private suspend fun registerParent(client: io.ktor.client.HttpClient, email: String): String {
        val body = postJson(client, "/api/v1/auth/register",
            """{"email":"$email","password":"correct horse battery"}""",
            expected = HttpStatusCode.Created)
        return json.parseToJsonElement(body).jsonObject.getValue("token").jsonPrimitive.content
    }

    private fun deviceKeyPair(): Pair<String, String> {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        val pair = generator.generateKeyPair()
        return Base64.getEncoder().encodeToString(pair.public.encoded) to
            Base64.getEncoder().encodeToString(pair.private.encoded)
    }

    private suspend fun registerDevice(
        client: io.ktor.client.HttpClient,
        deviceId: String = "device-" + java.util.UUID.randomUUID()
    ): Triple<String, String, String> {
        val publicKey = deviceKeyPair().first
        val body = postJson(client, "/api/v1/device/register",
            """{"device_id":"$deviceId","public_key":"$publicKey","name":"Child tablet","platform":"android"}""",
            expected = HttpStatusCode.Created)
        val parsed = json.parseToJsonElement(body).jsonObject
        return Triple(
            parsed.getValue("device_token").jsonPrimitive.content,
            parsed.getValue("pairing_code").jsonPrimitive.content,
            deviceId
        )
    }

    private suspend fun claim(
        client: io.ktor.client.HttpClient,
        parentToken: String,
        code: String,
        expected: HttpStatusCode = HttpStatusCode.OK
    ): String {
        return postJson(client, "/api/v1/pairing/claim", """{"code":"$code"}""",
            parentToken, expected)
    }

    private fun policy(
        budgets: String = """{"com.example.game":60}""",
        schedules: String = "{}",
        blocked: String = """["com.example.blocked"]""",
        extra: String = ""
    ): String = """{
        "schema":1,"revision":99,"timezone":"Asia/Shanghai","enabled":true,
        "launcher_enabled":false,"quiz_gate_enabled":true,"reward_minutes":10,
        "max_attempts_per_day":8,"cooldown_seconds":60$extra,
        "daily_budgets":$budgets,"blocked_packages":$blocked,"schedules":$schedules
    }"""

    private fun quizPayload(version: Long, questionPrompt: String = "7 + 5 = ?"): String = """{
        "schema":1,"version":$version,"published_at":"2026-11-13T00:00:00Z","questions":[
          {"id":"q1","type":"single_choice","prompt":"$questionPrompt","options":["10","12","13"],"answer_index":1,
           "subject":"mathematics","grade":3,"difficulty":"easy","language":"en","tags":["math","arithmetic"]},
          {"id":"q2","type":"multiple_choice","prompt":"Select even numbers","options":["1","2","3","4"],"answer_indices":[1,3],
           "subject":"mathematics","grade":3,"difficulty":"easy","language":"en","tags":["math"]},
          {"id":"q3","type":"true_false","prompt":"A triangle has three sides","answer":true,
           "subject":"geometry","grade":3,"difficulty":"easy","language":"en","tags":["shapes"]},
          {"id":"q4","type":"short_answer","prompt":"What is 6 x 6?","accepted_answers":["36","thirty-six"],
           "subject":"mathematics","grade":3,"difficulty":"easy","language":"en","tags":["arithmetic"]}
        ]
    }"""

    private fun quizUpload(
        pair: KeyPair,
        version: Long,
        activate: Boolean = true,
        questionPrompt: String = "7 + 5 = ?",
        signatureOverride: String? = null
    ): String {
        val payload = quizPayload(version, questionPrompt)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        val signature = signatureOverride
            ?: Base64.getEncoder().encodeToString(signer.sign())
        val keyId = digestHex("SHA-256", pair.public.encoded)
        val envelope = JsonObject(mapOf(
            "schema" to JsonPrimitive(1),
            "key_id" to JsonPrimitive(keyId),
            "payload" to JsonPrimitive(payload),
            "signature" to JsonPrimitive(signature)
        )).toString()
        return JsonObject(mapOf(
            "title" to JsonPrimitive("Grade 3 practice"),
            "public_key" to JsonPrimitive(Base64.getEncoder().encodeToString(pair.public.encoded)),
            "envelope" to JsonPrimitive(envelope),
            "activate" to JsonPrimitive(activate)
        )).toString()
    }

    private fun quizDeltaUpload(
        pair: KeyPair,
        version: Long,
        baseVersion: Long,
        operations: String,
        questionPrompt: String = "5 + 5 = ?",
        deltaSignatureOverride: String? = null
    ): String {
        val fullPayload = quizPayload(version, questionPrompt)
        val fullSigner = Signature.getInstance("SHA256withECDSA")
        fullSigner.initSign(pair.private)
        fullSigner.update(fullPayload.toByteArray(Charsets.UTF_8))
        val keyId = digestHex("SHA-256", pair.public.encoded)
        val fullEnvelope = JsonObject(mapOf(
            "schema" to JsonPrimitive(1),
            "key_id" to JsonPrimitive(keyId),
            "payload" to JsonPrimitive(fullPayload),
            "signature" to JsonPrimitive(
                Base64.getEncoder().encodeToString(fullSigner.sign()))
        )).toString()

        val deltaPayload = """{"schema":2,"version":$version,"base_version":$baseVersion,"published_at":"2026-11-13T00:00:00Z","operations":$operations}"""
        val deltaSigner = Signature.getInstance("SHA256withECDSA")
        deltaSigner.initSign(pair.private)
        deltaSigner.update(deltaPayload.toByteArray(Charsets.UTF_8))
        val deltaSignature = deltaSignatureOverride
            ?: Base64.getEncoder().encodeToString(deltaSigner.sign())
        val deltaEnvelope = JsonObject(mapOf(
            "schema" to JsonPrimitive(1),
            "key_id" to JsonPrimitive(keyId),
            "payload" to JsonPrimitive(deltaPayload),
            "signature" to JsonPrimitive(deltaSignature)
        )).toString()
        return JsonObject(mapOf(
            "title" to JsonPrimitive("Grade 3 delta"),
            "public_key" to JsonPrimitive(Base64.getEncoder().encodeToString(pair.public.encoded)),
            "envelope" to JsonPrimitive(fullEnvelope),
            "delta_envelope" to JsonPrimitive(deltaEnvelope),
            "base_version" to JsonPrimitive(baseVersion),
            "activate" to JsonPrimitive(true)
        )).toString()
    }

    private fun quizKeyTransition(
        oldPair: KeyPair,
        newPair: KeyPair,
        signingPair: KeyPair = oldPair,
        oldKeyId: String = digestHex("SHA-256", oldPair.public.encoded),
        newKeyId: String = digestHex("SHA-256", newPair.public.encoded),
        notBefore: Long = System.currentTimeMillis()
    ): String {
        val newPublicKey = Base64.getEncoder().encodeToString(newPair.public.encoded)
        val message = QuizKeyTransitionValidator.transitionMessage(
            oldKeyId, newKeyId, newPublicKey, notBefore
        )
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(signingPair.private)
        signer.update(message.toByteArray(Charsets.UTF_8))
        return JsonObject(mapOf(
            "schema" to JsonPrimitive(1),
            "type" to JsonPrimitive("quiz-key-transition"),
            "old_key_id" to JsonPrimitive(oldKeyId),
            "new_key_id" to JsonPrimitive(newKeyId),
            "new_public_key" to JsonPrimitive(newPublicKey),
            "not_before" to JsonPrimitive(notBefore),
            "signature" to JsonPrimitive(
                Base64.getEncoder().encodeToString(signer.sign()))
        )).toString()
    }

    private fun bind(statement: PreparedStatement, parameters: Array<out Any?>) {
        parameters.forEachIndexed { index, value ->
            when (value) {
                is String -> statement.setString(index + 1, value)
                is Int -> statement.setInt(index + 1, value)
                is Long -> statement.setLong(index + 1, value)
                is Boolean -> statement.setBoolean(index + 1, value)
                is ByteArray -> statement.setBytes(index + 1, value)
                is Instant -> statement.setTimestamp(index + 1, Timestamp.from(value))
                is LocalDate -> statement.setDate(index + 1, java.sql.Date.valueOf(value))
                null -> statement.setObject(index + 1, null)
                else -> error("Unsupported SQL test parameter: ${value.javaClass.name}")
            }
        }
    }

    private fun execute(services: Services, sql: String, vararg parameters: Any?) {
        services.database.withConnection { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement, parameters)
                assertEquals(1, statement.executeUpdate(), sql)
            }
        }
    }

    private fun scalar(services: Services, sql: String, vararg parameters: Any?): Int =
        services.database.withConnection { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement, parameters)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    rows.getInt(1)
                }
            }
        }

    private fun seedAccountData(services: Services, parentId: String, deviceId: String) {
        val suffix = java.util.UUID.randomUUID().toString()
        val now = Instant.now()
        val expires = now.plusSeconds(3600)
        execute(services,
            "INSERT INTO policy_revisions (id, device_id, revision, payload, signature, key_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            "policy-$suffix", deviceId, 1L, "{}", "signature", "key", now)
        execute(services,
            "INSERT INTO usage_reports (id, device_id, usage_date, payload, created_at) VALUES (?, ?, ?, ?, ?)",
            "usage-$suffix", deviceId, LocalDate.now(), "{}", now)
        execute(services,
            "INSERT INTO audit_events (id, device_id, actor, event, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            "audit-$suffix", deviceId, "test", "seeded", "", now)
        execute(services,
            "INSERT INTO call_requests (id, device_id, kind, status, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
            "call-$suffix", deviceId, "video", "pending", now, expires)
        execute(services,
            "INSERT INTO device_apps (device_id, package_name, label, category, enabled, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            deviceId, "com.example.seeded", "Seeded app", 0, true, now)
        execute(services,
            "INSERT INTO temporary_grants (id, device_id, package_name, minutes, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
            "grant-$suffix", deviceId, "com.example.seeded", 10, now, expires)
        execute(services,
            "INSERT INTO quiz_packs (id, parent_id, title, version, key_id, public_key, envelope, question_count, published_at, active, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "quiz-$suffix", parentId, "Seeded pack", 1L, "key", "public-key", "envelope", 1,
            now.toString(), true, now)
        execute(services,
            "INSERT INTO screenshots (id, device_id, call_id, content_type, width, height, image, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "screenshot-$suffix", deviceId, "call-$suffix", "image/jpeg", 1, 1,
            byteArrayOf(1, 2), now, expires)
        execute(services,
            "INSERT INTO quiz_attempt_results (id, device_id, attempt_id, occurred_at, target_package, policy_revision, quiz_version, question_count, correct_count, reward_granted, questions, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "result-$suffix", deviceId, "attempt-$suffix", now, "com.example.seeded", 1L, 1L,
            1, 1, true, "[]", now)
        execute(services,
            "INSERT INTO device_notices (id, device_id, kind, message, status, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            "notice-$suffix", deviceId, "info", "Seeded notice", "pending", now, expires)
        execute(services,
            "INSERT INTO manual_quiz_triggers (id, device_id, parent_id, target_package, status, created_at, expires_at, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            "trigger-$suffix", deviceId, parentId, "com.example.seeded", "pending", now, expires, "")
        execute(services,
            "INSERT INTO quiz_upstream_sources (parent_id, title, url, public_key, key_id, enabled, interval_minutes, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            parentId, "Seeded upstream", "upstream.example.test/pack", "public-key", "key",
            true, 60, now, now)
        execute(services,
            "INSERT INTO quiz_upstream_events (id, parent_id, event, detail, created_at) VALUES (?, ?, ?, ?, ?)",
            "upstream-event-$suffix", parentId, "sync.ok", "", now)
        execute(services,
            "INSERT INTO help_requests (id, device_id, parent_id, client_request_id, reason, message, status, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "help-$suffix", deviceId, parentId, "client-$suffix", "blocked", "Please help", "pending", now, expires)
        execute(services,
            "INSERT INTO quiz_key_transitions (id, parent_id, old_key_id, new_key_id, new_public_key, transition, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            "key-transition-$suffix", parentId, "old-key", "new-key", "public-key", "transition", now)
    }

    private fun assertAccountData(
        services: Services,
        parentId: String,
        deviceId: String,
        expected: Int,
        atLeast: Boolean = false
    ) {
        val deviceTables = listOf(
            "policy_revisions", "usage_reports", "audit_events", "call_requests", "device_apps",
            "temporary_grants", "screenshots", "quiz_attempt_results", "device_notices",
            "manual_quiz_triggers", "help_requests"
        )
        for (table in deviceTables) {
            val count = scalar(services, "SELECT COUNT(*) FROM $table WHERE device_id = ?", deviceId)
            if (atLeast) assertTrue(count >= expected, "$table: $count < $expected")
            else assertEquals(expected, count, table)
        }
        val parentTables = listOf(
            "quiz_packs", "quiz_upstream_sources", "quiz_upstream_events", "quiz_key_transitions"
        )
        for (table in parentTables) {
            val count = scalar(services, "SELECT COUNT(*) FROM $table WHERE parent_id = ?", parentId)
            if (atLeast) assertTrue(count >= expected, "$table: $count < $expected")
            else assertEquals(expected, count, table)
        }
        val deviceCount = scalar(services, "SELECT COUNT(*) FROM devices WHERE parent_id = ?", parentId)
        val parentCount = scalar(services, "SELECT COUNT(*) FROM parents WHERE id = ?", parentId)
        if (atLeast) {
            assertTrue(deviceCount >= expected, "devices: $deviceCount < $expected")
            assertTrue(parentCount >= expected, "parents: $parentCount < $expected")
        } else {
            assertEquals(expected, deviceCount, "devices")
            assertEquals(expected, parentCount, "parents")
        }
    }

    @Test
    fun quizPolicyFiltersAreNormalizedAndValidated() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "quiz-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val quizFields = ""","quiz_question_count":5,"quiz_subjects":["Reading","Math"],
            "quiz_grades":[7,2],"quiz_difficulties":["Hard","easy"],
            "quiz_languages":["zh-CN","en"],"quiz_tags":["Fractions","reading"]"""
        val published = putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy(extra = quizFields)}}""", parentToken)
        val payload = json.parseToJsonElement(published).jsonObject
            .getValue("payload").jsonPrimitive.content
        val normalized = json.parseToJsonElement(payload).jsonObject
        assertEquals(5, normalized.getValue("quiz_question_count").jsonPrimitive.int)
        assertEquals("[\"math\",\"reading\"]",
            normalized.getValue("quiz_subjects").toString())
        assertEquals("[2,7]", normalized.getValue("quiz_grades").toString())
        assertEquals("[\"easy\",\"hard\"]",
            normalized.getValue("quiz_difficulties").toString())
        assertEquals("[\"en\",\"zh-cn\"]",
            normalized.getValue("quiz_languages").toString())
        assertEquals("[\"fractions\",\"reading\"]",
            normalized.getValue("quiz_tags").toString())

        val invalidFields = listOf(
            ""","quiz_question_count":0""",
            ""","quiz_question_count":11""",
            ""","quiz_grades":[13]""",
            ""","quiz_difficulties":["extreme"]""",
            ""","quiz_languages":["zh_CN"]""",
            ""","quiz_subjects":["Math","math"]""",
            ""","quiz_tags":123"""
        )
        invalidFields.forEach { fields ->
            putJson(client, "/api/v1/devices/$deviceUuid/policy",
                """{"policy":${policy(extra = fields)}}""",
                parentToken, HttpStatusCode.BadRequest)
        }
    }

    @Test
    fun helpContactsAreValidatedNormalizedAndBounded() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client,
            "contacts-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val contacts = ""","help_contacts":[
            {"label":" Mum ","phone":"+86 138 0000 0000"},
            {"label":"School office","phone":"110"}
        ]"""
        val published = putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy(extra = contacts)}}""", parentToken)
        val payload = json.parseToJsonElement(published).jsonObject
            .getValue("payload").jsonPrimitive.content
        val normalized = json.parseToJsonElement(payload).jsonObject
            .getValue("help_contacts").jsonArray
        assertEquals(2, normalized.size)
        assertEquals("Mum", normalized[0].jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals("+86 138 0000 0000",
            normalized[0].jsonObject.getValue("phone").jsonPrimitive.content)
        assertEquals("110", normalized[1].jsonObject.getValue("phone").jsonPrimitive.content)

        val invalid = listOf(
            ""","help_contacts":[{"label":"A","phone":"1"}]""",
            ""","help_contacts":[{"label":"","phone":"110"}]""",
            ""","help_contacts":[{"label":"A","phone":"call me"}]""",
            ""","help_contacts":[{"label":"A","phone":"110","extra":true}]""",
            ""","help_contacts":[
                {"label":"A","phone":"110"},{"label":"B","phone":"111"},
                {"label":"C","phone":"112"},{"label":"D","phone":"113"}
            ]""",
            ""","help_contacts":[{"label":"A","phone":"110"},{"label":"A","phone":"110"}]"""
        )
        invalid.forEach { extra ->
            putJson(client, "/api/v1/devices/$deviceUuid/policy",
                """{"policy":${policy(extra = extra)}}""",
                parentToken, HttpStatusCode.BadRequest)
        }
    }

    @Test
    fun alwaysAllowedPackagesAreNormalizedAndCannotBeQuizTargets() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client,
            "always-allowed-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val allowed = ""","always_allowed_packages":["com.example.video","com.example.phone"]"""
        val published = putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy(extra = allowed)}}""", parentToken)
        val payload = json.parseToJsonElement(published).jsonObject
            .getValue("payload").jsonPrimitive.content
        val normalized = json.parseToJsonElement(payload).jsonObject
        assertEquals("""["com.example.phone","com.example.video"]""",
            normalized.getValue("always_allowed_packages").toString())

        postJson(client, "/api/v1/devices/$deviceUuid/quiz-triggers",
            """{"package_name":"com.example.phone"}""", parentToken,
            HttpStatusCode.Conflict)

        val invalid = listOf(
            ""","always_allowed_packages":["com.example.phone","com.example.phone"]""",
            ""","always_allowed_packages":["bad package"]""",
            ""","always_allowed_packages":["com.example.blocked"]""",
            ""","always_allowed_packages":[42]"""
        )
        invalid.forEach { extra ->
            putJson(client, "/api/v1/devices/$deviceUuid/policy",
                """{"policy":${policy(extra = extra)}}""",
                parentToken, HttpStatusCode.BadRequest)
        }

        val trigger = ""","quiz_triggers":[
            {"type":"app_enter","packages":["com.example.phone"],"delay_seconds":0}
        ],"always_allowed_packages":["com.example.phone"]"""
        putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy(
                budgets = """{"com.example.game":60,"com.example.phone":30}""",
                extra = trigger
            )}}""", parentToken, HttpStatusCode.BadRequest)
    }

    @Test
    fun usageReminderThresholdsAreOptionalNormalizedAndBounded() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client,
            "usage-reminders-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val thresholds = ""","budget_warning_minutes":15,
            "continuous_use_reminder_minutes":30"""
        val published = putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy(extra = thresholds)}}""", parentToken)
        val normalized = json.parseToJsonElement(json.parseToJsonElement(published)
            .jsonObject.getValue("payload").jsonPrimitive.content).jsonObject
        assertEquals(15, normalized.getValue("budget_warning_minutes").jsonPrimitive.int)
        assertEquals(30,
            normalized.getValue("continuous_use_reminder_minutes").jsonPrimitive.int)

        val defaults = putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy()}}""", parentToken)
        val defaultPayload = json.parseToJsonElement(json.parseToJsonElement(defaults)
            .jsonObject.getValue("payload").jsonPrimitive.content).jsonObject
        assertEquals(0, defaultPayload.getValue("budget_warning_minutes").jsonPrimitive.int)
        assertEquals(0,
            defaultPayload.getValue("continuous_use_reminder_minutes").jsonPrimitive.int)

        listOf(
            ""","budget_warning_minutes":-1""",
            ""","budget_warning_minutes":1441""",
            ""","budget_warning_minutes":1.5""",
            ""","continuous_use_reminder_minutes":-1""",
            ""","continuous_use_reminder_minutes":1441""",
            ""","continuous_use_reminder_minutes":"30" """
        ).forEach { extra ->
            putJson(client, "/api/v1/devices/$deviceUuid/policy",
                """{"policy":${policy(extra = extra)}}""",
                parentToken, HttpStatusCode.BadRequest)
        }
    }

    @Test
    fun quizTriggersAreValidatedNormalizedAndStable() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "triggers-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val triggers = ""","quiz_triggers":[
            {"type":"total_usage","packages":["com.example.game"],"minutes":30},
            {"type":"app_enter","packages":["com.example.game"],"delay_seconds":15},
            {"type":"continuous_use","packages":["com.example.game"],"minutes":20},
            {"type":"schedule_start","packages":["com.example.game"],"days":[5,1],"start_minute":960}
        ]"""
        val first = putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy(extra = triggers)}}""", parentToken)
        val firstPayload = json.parseToJsonElement(first).jsonObject
            .getValue("payload").jsonPrimitive.content
        val normalized = json.parseToJsonElement(firstPayload).jsonObject
        val normalizedTriggers = normalized.getValue("quiz_triggers").jsonArray.map { it.jsonObject }
        assertEquals(4, normalizedTriggers.size)
        val ids = normalizedTriggers.map { it.getValue("id").jsonPrimitive.content }
        assertTrue(ids.all { it.matches(Regex("quiz-trigger-[a-f0-9]{24}")) })
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("[\"com.example.game\"]",
            normalizedTriggers[1].getValue("packages").toString())
        assertEquals("[1,5]", normalizedTriggers[3].getValue("days").toString())

        val second = putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy(extra = triggers)}}""", parentToken)
        val secondPayload = json.parseToJsonElement(second).jsonObject
            .getValue("payload").jsonPrimitive.content
        val secondIds = json.parseToJsonElement(secondPayload).jsonObject
            .getValue("quiz_triggers").jsonArray.map {
                it.jsonObject.getValue("id").jsonPrimitive.content
            }
        assertEquals(ids, secondIds)

        val invalidTriggers = listOf(
            ""","quiz_triggers":[{"type":"total_usage","packages":["com.example.game"],"minutes":30,"extra":true}]""",
            ""","quiz_triggers":[{"type":"total_usage","packages":["com.example.game"],"minutes":30},{"type":"total_usage","packages":["com.example.game"],"minutes":30}]""",
            ""","quiz_triggers":[{"type":"app_enter","packages":["com.example.unmanaged"]}]""",
            ""","quiz_triggers":[{"type":"app_enter","packages":["com.example.game"],"delay_seconds":3601}]""",
            ""","quiz_triggers":[{"type":"continuous_use","packages":["com.example.game"]}]""",
            ""","quiz_triggers":[{"type":"schedule_start","packages":["com.example.game"],"days":[],"start_minute":960}]""",
            ""","quiz_triggers":[{"type":"schedule_start","packages":["com.example.game"],"days":[1,1],"start_minute":960}]""",
            ""","quiz_triggers":[{"type":"manual","packages":["com.example.game"]}]"""
        )
        invalidTriggers.forEach { extra ->
            putJson(client, "/api/v1/devices/$deviceUuid/policy",
                """{"policy":${policy(extra = extra)}}""",
                parentToken, HttpStatusCode.BadRequest)
        }

        val blockedTrigger = ""","quiz_triggers":[
            {"type":"app_enter","packages":["com.example.blocked"]}
        ]"""
        putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy(
                budgets = """{"com.example.game":60,"com.example.blocked":0}""",
                extra = blockedTrigger
            )}}""", parentToken, HttpStatusCode.BadRequest)
    }

    @Test
    fun healthAndPolicyKeyArePublic() = testApplication {
        val services = services()
        application { guardModule(services) }
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        val key = client.get("/api/v1/config/policy-key")
        assertEquals(HttpStatusCode.OK, key.status)
        val parsed = json.parseToJsonElement(key.bodyAsText()).jsonObject
        assertEquals(services.config.policySigner.keyId, parsed.getValue("key_id").jsonPrimitive.content)
        assertTrue(parsed.getValue("public_key").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun malformedJsonBodiesReturnBadRequest() = testApplication {
        val services = services()
        application { guardModule(services) }

        val malformed = postJson(client, "/api/v1/auth/login", "{\"email\":",
            expected = HttpStatusCode.BadRequest)
        assertEquals("请求无效",
            json.parseToJsonElement(malformed).jsonObject.getValue("error").jsonPrimitive.content)

        postJson(client, "/api/v1/auth/login",
            """{"email":42,"password":"correct horse battery"}""",
            expected = HttpStatusCode.BadRequest)
    }

    @Test
    fun quizResultsAreIdempotentPrivateAndValidated() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "results-${java.util.UUID.randomUUID()}@example.test")
        val otherToken = registerParent(client, "results-other-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val attempt = "attempt-${java.util.UUID.randomUUID()}"
        val body = """{
            "attempt_id":"$attempt","occurred_at":${System.currentTimeMillis()},
            "target_package":"com.example.game","trigger_id":null,
            "policy_revision":3,"quiz_version":9,"reward_granted":false,
            "questions":[
              {"question_id":"q1","correct":false,"type":"single_choice",
               "subject":"Mathematics","grade":3,"difficulty":"Easy","language":"EN",
               "tags":["Math","math"]}
            ]
        }"""
        postJson(client, "/api/v1/device/quiz-results", body, deviceToken,
            HttpStatusCode.NoContent)
        postJson(client, "/api/v1/device/quiz-results", body, deviceToken,
            HttpStatusCode.NoContent)

        val listed = client.get("/api/v1/devices/$deviceUuid/quiz-results") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.OK, listed.status)
        val results = json.parseToJsonElement(listed.bodyAsText()).jsonArray
        assertEquals(1, results.size)
        val result = results.single().jsonObject
        assertEquals(0, result.getValue("correct_count").jsonPrimitive.int)
        assertEquals(1, result.getValue("question_count").jsonPrimitive.int)
        val question = result.getValue("questions").jsonArray.single().jsonObject
        assertEquals("mathematics", question.getValue("subject").jsonPrimitive.content)
        assertEquals("[\"math\"]", question.getValue("tags").toString())

        assertEquals(HttpStatusCode.NotFound,
            client.get("/api/v1/devices/$deviceUuid/quiz-results") {
                header(HttpHeaders.Authorization, "Bearer $otherToken")
            }.status)

        val badReward = body.replace("\"reward_granted\":false", "\"reward_granted\":true")
            .replace(attempt, "attempt-${java.util.UUID.randomUUID()}")
        postJson(client, "/api/v1/device/quiz-results", badReward, deviceToken,
            HttpStatusCode.BadRequest)
        val badTrigger = body.replace("\"trigger_id\":null", "\"trigger_id\":\"forged\"")
            .replace(attempt, "attempt-${java.util.UUID.randomUUID()}")
        postJson(client, "/api/v1/device/quiz-results", badTrigger, deviceToken,
            HttpStatusCode.BadRequest)
    }

    @Test
    fun dashboardAggregatesUsageQuizResultsAndCurrentApp() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client,
            "dashboard-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)
        putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy()}}""", parentToken)
        postJson(client, "/api/v1/device/apps", """{"apps":[
            {"package_name":"com.example.game","label":"Game","category":0,"enabled":true},
            {"package_name":"com.example.video","label":"Video","category":0,"enabled":true}
        ]}""", deviceToken, HttpStatusCode.NoContent)

        val day = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
        val now = System.currentTimeMillis()
        postJson(client, "/api/v1/device/usage", """{
            "day":"$day",
            "app_minutes":{"com.example.game":18,"com.example.video":4},
            "health":{"current_app":"com.example.game","current_app_observed_at":$now,
                "device_owner":true,"usage_access":true,"policy_enabled":true}
        }""", deviceToken, HttpStatusCode.NoContent)
        postJson(client, "/api/v1/device/quiz-results", """{
            "attempt_id":"dashboard-attempt-1","occurred_at":$now,
            "target_package":"com.example.game","trigger_id":null,
            "policy_revision":1,"quiz_version":1,"reward_granted":false,
            "questions":[
              {"question_id":"q1","correct":true,"type":"single_choice",
               "subject":"mathematics","grade":3,"difficulty":"easy","language":"zh-cn","tags":["math"]},
              {"question_id":"q2","correct":true,"type":"single_choice",
               "subject":"mathematics","grade":3,"difficulty":"easy","language":"zh-cn","tags":["math"]},
              {"question_id":"q3","correct":false,"type":"single_choice",
               "subject":"english","grade":3,"difficulty":"easy","language":"zh-cn","tags":["word"]}
            ]
        }""", deviceToken, HttpStatusCode.NoContent)

        val response = client.get("/api/v1/devices/$deviceUuid/dashboard?date=$day") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val dashboard = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(22, dashboard.getValue("today_minutes").jsonPrimitive.int)
        assertEquals(1, dashboard.getValue("quiz_attempts").jsonPrimitive.int)
        assertEquals(3, dashboard.getValue("quiz_questions").jsonPrimitive.int)
        assertEquals(2, dashboard.getValue("quiz_correct").jsonPrimitive.int)
        assertEquals(67, dashboard.getValue("quiz_accuracy_percent").jsonPrimitive.int)
        assertEquals("com.example.game", dashboard.getValue("current_app").jsonPrimitive.content)
        assertEquals("Game", dashboard.getValue("current_app_label").jsonPrimitive.content)
        val apps = dashboard.getValue("app_usage").jsonArray
        assertEquals(2, apps.size)
        assertEquals("Game", apps[0].jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals(82, apps[0].jsonObject.getValue("percent").jsonPrimitive.int)
        assertEquals(7, dashboard.getValue("days").jsonArray.size)
    }

    @Test
    fun parentNoticesAreVisibleToTheChildAndAcknowledgedOnce() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "notice-${java.util.UUID.randomUUID()}@example.test")
        val otherToken = registerParent(client, "notice-other-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        val otherDeviceUuid = json.parseToJsonElement(claim(client, otherToken,
            registerDevice(client).second)).jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val created = json.parseToJsonElement(postJson(client,
            "/api/v1/devices/$deviceUuid/notices",
            """{"kind":"callback","message":"请回拨"}""", parentToken,
            HttpStatusCode.Created)).jsonObject
        val noticeId = created.getValue("id").jsonPrimitive.content
        assertEquals("pending", created.getValue("status").jsonPrimitive.content)

        val pending = json.parseToJsonElement(client.get("/api/v1/device/notices") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }.bodyAsText()).jsonArray
        assertEquals(1, pending.size)
        assertEquals("请回拨", pending.single().jsonObject.getValue("message").jsonPrimitive.content)

        postJson(client, "/api/v1/device/notices/$noticeId/ack", "{}", deviceToken)
        assertEquals(0, json.parseToJsonElement(client.get("/api/v1/device/notices") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }.bodyAsText()).jsonArray.size)
        postJson(client, "/api/v1/device/notices/$noticeId/ack", "{}", deviceToken,
            HttpStatusCode.Conflict)

        val history = json.parseToJsonElement(client.get("/api/v1/devices/$deviceUuid/notices") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }.bodyAsText()).jsonArray
        assertEquals("acknowledged",
            history.single().jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound,
            client.post("/api/v1/devices/$otherDeviceUuid/notices") {
                contentType(ContentType.Application.Json)
                setBody("""{"kind":"task"}""")
                header(HttpHeaders.Authorization, "Bearer $parentToken")
            }.status)
        postJson(client, "/api/v1/devices/$deviceUuid/notices",
            """{"kind":"unknown"}""", parentToken, HttpStatusCode.BadRequest)
    }

    @Test
    fun manualQuizTriggerIsIdempotentDeliveredOnceAndCompletedByResult() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client,
            "manual-quiz-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)
        putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy()}}""", parentToken)

        postJson(client, "/api/v1/devices/$deviceUuid/quiz-triggers",
            """{"package_name":"com.example.unmanaged"}""", parentToken,
            HttpStatusCode.Conflict)
        postJson(client, "/api/v1/devices/$deviceUuid/quiz-triggers",
            """{"package_name":"com.example.blocked"}""", parentToken,
            HttpStatusCode.Conflict)

        val first = json.parseToJsonElement(postJson(client,
            "/api/v1/devices/$deviceUuid/quiz-triggers",
            """{"package_name":"com.example.game","expires_in_minutes":5}""",
            parentToken, HttpStatusCode.Created)).jsonObject
        val triggerId = first.getValue("id").jsonPrimitive.content
        assertTrue(triggerId.matches(Regex("quiz-manual-[a-f0-9]{24}")))
        assertEquals("pending", first.getValue("status").jsonPrimitive.content)

        val repeated = json.parseToJsonElement(postJson(client,
            "/api/v1/devices/$deviceUuid/quiz-triggers",
            """{"package_name":"com.example.game"}""",
            parentToken, HttpStatusCode.Created)).jsonObject
        assertEquals(triggerId, repeated.getValue("id").jsonPrimitive.content)

        val pending = json.parseToJsonElement(client.get("/api/v1/device/quiz-triggers") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }.bodyAsText()).jsonArray
        assertEquals(1, pending.size)
        assertEquals(triggerId,
            pending.single().jsonObject.getValue("id").jsonPrimitive.content)

        val accepted = json.parseToJsonElement(postJson(client,
            "/api/v1/device/quiz-triggers/$triggerId/ack",
            """{"status":"accepted"}""", deviceToken)).jsonObject
        assertEquals("accepted", accepted.getValue("status").jsonPrimitive.content)
        assertNotNull(accepted["delivered_at"])
        assertEquals(0, json.parseToJsonElement(client.get("/api/v1/device/quiz-triggers") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }.bodyAsText()).jsonArray.size)
        postJson(client, "/api/v1/device/quiz-triggers/$triggerId/ack",
            """{"status":"unknown"}""", deviceToken, HttpStatusCode.BadRequest)

        postJson(client, "/api/v1/device/quiz-results", """{
            "attempt_id":"attempt-manual-${java.util.UUID.randomUUID()}",
            "occurred_at":${System.currentTimeMillis()},
            "target_package":"com.example.game","trigger_id":"$triggerId",
            "policy_revision":1,"quiz_version":1,"reward_granted":true,
            "questions":[{"question_id":"q1","correct":true}]
        }""", deviceToken, HttpStatusCode.NoContent)

        val history = json.parseToJsonElement(client.get(
            "/api/v1/devices/$deviceUuid/quiz-triggers") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }.bodyAsText()).jsonArray
        assertEquals("completed",
            history.single().jsonObject.getValue("status").jsonPrimitive.content)
        val audit = json.parseToJsonElement(client.get(
            "/api/v1/devices/$deviceUuid/audit") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }.bodyAsText()).jsonArray.map { it.jsonObject.getValue("event").jsonPrimitive.content }
        assertTrue(audit.contains("quiz.trigger.manual"))
        assertTrue(audit.contains("quiz.trigger.accepted"))
        assertTrue(audit.contains("quiz.result"))
    }

    @Test
    fun registrationRejectsDuplicateEmailAndLoginChecksPassword() = testApplication {
        val services = services()
        application { guardModule(services) }
        val email = "parent-${java.util.UUID.randomUUID()}@example.test"
        registerParent(client, email)
        postJson(client, "/api/v1/auth/register",
            """{"email":"$email","password":"correct horse battery"}""",
            expected = HttpStatusCode.Conflict)
        postJson(client, "/api/v1/auth/login",
            """{"email":"$email","password":"the wrong password"}""",
            expected = HttpStatusCode.Unauthorized)
        postJson(client, "/api/v1/auth/login",
            """{"email":"$email","password":"correct horse battery"}""")
    }

    @Test
    fun pairingPolicySignatureAndIncrementalSync() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "p-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val claimBody = claim(client, parentToken, code)
        val device = json.parseToJsonElement(claimBody).jsonObject
        val deviceUuid = device.getValue("id").jsonPrimitive.content
        assertEquals(false, device.getValue("confirmed").jsonPrimitive.content.toBoolean())
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val published = putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy()}}""", parentToken)
        val envelope = json.parseToJsonElement(published).jsonObject
        assertEquals(1, envelope.getValue("revision").jsonPrimitive.int)
        assertEquals(services.config.policySigner.keyId,
            envelope.getValue("key_id").jsonPrimitive.content)
        val payload = envelope.getValue("payload").jsonPrimitive.content
        val signature = Base64.getDecoder().decode(envelope.getValue("signature").jsonPrimitive.content)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(services.config.policySigner.publicKey)
        verifier.update(payload.toByteArray(Charsets.UTF_8))
        assertTrue(verifier.verify(signature))
        assertEquals(1, json.parseToJsonElement(payload).jsonObject.getValue("revision").jsonPrimitive.int)

        val second = putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy(schedules = """{"com.example.game":[{"days":[1,2,3,4,5],"start_minute":960,"duration_minutes":120}]}""")}}""",
            parentToken)
        assertEquals(2, json.parseToJsonElement(second).jsonObject.getValue("revision").jsonPrimitive.int)

        val synced = client.get("/api/v1/device/policy?after=1") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.OK, synced.status)
        assertEquals(2, json.parseToJsonElement(synced.bodyAsText()).jsonObject
            .getValue("revision").jsonPrimitive.int)
    }

    @Test
    fun invalidPoliciesAreRejected() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "invalid-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy().replace("Asia/Shanghai", "Mars/Olympus")}}""",
            parentToken, HttpStatusCode.BadRequest)
        putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy().replace("\"revision\":99", "\"revision\":99,\"unknown\":1")}}""",
            parentToken, HttpStatusCode.BadRequest)
        putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy(schedules = """{"com.example.game":[{"days":[1],"start_minute":960,"duration_minutes":0}]}""")}}""",
            parentToken, HttpStatusCode.BadRequest)
    }

    @Test
    fun appInventoryAndTemporaryGrantsAreScopedAndIdempotent() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "apps-${java.util.UUID.randomUUID()}@example.test")
        val otherToken = registerParent(client, "apps-other-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)
        putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy(
                budgets = """{"com.example.game":60,"com.example.video":30}""",
                extra = ""","always_allowed_packages":["com.example.video"]"""
            )}}""", parentToken)

        postJson(client, "/api/v1/device/apps",
            """{"apps":[{"package_name":"com.example.game","label":"Example Game","category":0,"enabled":true},{"package_name":"com.example.unknown","label":"Unknown Category","category":-1,"enabled":true}]}""",
            deviceToken, HttpStatusCode.NoContent)
        val listed = client.get("/api/v1/devices/$deviceUuid/apps") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.OK, listed.status)
        val inventory = json.parseToJsonElement(listed.bodyAsText()).jsonArray.map { it.jsonObject }
        val app = inventory.single { it.getValue("package_name").jsonPrimitive.content == "com.example.game" }
        assertEquals("Example Game", app.getValue("label").jsonPrimitive.content)
        assertEquals(0, app.getValue("category").jsonPrimitive.int)
        val undefinedCategory = inventory.single {
            it.getValue("package_name").jsonPrimitive.content == "com.example.unknown"
        }
        assertEquals(-1, undefinedCategory.getValue("category").jsonPrimitive.int)
        val foreign = client.get("/api/v1/devices/$deviceUuid/apps") {
            header(HttpHeaders.Authorization, "Bearer $otherToken")
        }
        assertEquals(HttpStatusCode.NotFound, foreign.status)

        listOf("com.example.unmanaged", "com.example.blocked", "com.example.video").forEach {
            postJson(client, "/api/v1/devices/$deviceUuid/grants",
                """{"package_name":"$it","minutes":15,"expires_in_minutes":60}""",
                parentToken, HttpStatusCode.Conflict)
        }
        val created = postJson(client, "/api/v1/devices/$deviceUuid/grants",
            """{"package_name":"com.example.game","minutes":15,"expires_in_minutes":60}""",
            parentToken, HttpStatusCode.Created)
        val grantId = json.parseToJsonElement(created).jsonObject.getValue("id").jsonPrimitive.content
        val pending = client.get("/api/v1/device/grants") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.OK, pending.status)
        assertEquals(grantId, json.parseToJsonElement(pending.bodyAsText()).jsonArray
            .single().jsonObject.getValue("id").jsonPrimitive.content)

        val applied = postJson(client, "/api/v1/device/grants/$grantId/apply", "{}", deviceToken)
        assertEquals(true, json.parseToJsonElement(applied).jsonObject
            .getValue("applied").jsonPrimitive.content.toBoolean())
        val appliedAgain = postJson(client, "/api/v1/device/grants/$grantId/apply", "{}", deviceToken)
        assertEquals(true, json.parseToJsonElement(appliedAgain).jsonObject
            .getValue("applied").jsonPrimitive.content.toBoolean())
        val empty = client.get("/api/v1/device/grants") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.OK, empty.status)
        assertTrue(json.parseToJsonElement(empty.bodyAsText()).jsonArray.isEmpty())
    }

    @Test
    fun temporaryGrantRevocationIsParentScopedAndDeviceVisible() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "revoke-${java.util.UUID.randomUUID()}@example.test")
        val otherToken = registerParent(client, "revoke-other-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)
        putJson(client, "/api/v1/devices/$deviceUuid/policy",
            """{"policy":${policy(budgets = """{"com.example.game":60}""")}}""", parentToken)

        val created = postJson(client, "/api/v1/devices/$deviceUuid/grants",
            """{"package_name":"com.example.game","minutes":15,"expires_in_minutes":60}""",
            parentToken, HttpStatusCode.Created)
        val grantId = json.parseToJsonElement(created).jsonObject
            .getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/devices/$deviceUuid/grants/$grantId/revoke", "{}",
            otherToken, HttpStatusCode.NotFound)

        val revoked = postJson(client, "/api/v1/devices/$deviceUuid/grants/$grantId/revoke",
            "{}", parentToken)
        val revokedBody = json.parseToJsonElement(revoked).jsonObject
        assertEquals(true, revokedBody.getValue("revoked").jsonPrimitive.content.toBoolean())
        assertNotNull(revokedBody["revoked_at"])
        postJson(client, "/api/v1/devices/$deviceUuid/grants/$grantId/revoke", "{}", parentToken)

        val pending = client.get("/api/v1/device/grants") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.OK, pending.status)
        assertTrue(json.parseToJsonElement(pending.bodyAsText()).jsonArray.isEmpty())
        postJson(client, "/api/v1/device/grants/$grantId/apply", "{}", deviceToken,
            HttpStatusCode.Conflict)

        val appliedCreated = postJson(client, "/api/v1/devices/$deviceUuid/grants",
            """{"package_name":"com.example.game","minutes":20,"expires_in_minutes":60}""",
            parentToken, HttpStatusCode.Created)
        val appliedId = json.parseToJsonElement(appliedCreated).jsonObject
            .getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/grants/$appliedId/apply", "{}", deviceToken)
        postJson(client, "/api/v1/devices/$deviceUuid/grants/$appliedId/revoke", "{}", parentToken)

        val revocations = client.get("/api/v1/device/grants/revocations") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.OK, revocations.status)
        val revokedIds = json.parseToJsonElement(revocations.bodyAsText()).jsonArray.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
        }.toSet()
        assertEquals(setOf(grantId, appliedId), revokedIds)

        val listed = client.get("/api/v1/devices/$deviceUuid/grants") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.OK, listed.status)
        val listedById = json.parseToJsonElement(listed.bodyAsText()).jsonArray.associateBy {
            it.jsonObject.getValue("id").jsonPrimitive.content
        }
        assertEquals(true, listedById.getValue(appliedId).jsonObject
            .getValue("applied").jsonPrimitive.content.toBoolean())
        assertEquals(true, listedById.getValue(appliedId).jsonObject
            .getValue("revoked").jsonPrimitive.content.toBoolean())

        val audit = client.get("/api/v1/devices/$deviceUuid/audit") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.OK, audit.status)
        val revokeEvents = json.parseToJsonElement(audit.bodyAsText()).jsonArray.count {
            it.jsonObject.getValue("event").jsonPrimitive.content == "grant.revoked"
        }
        assertEquals(2, revokeEvents)
    }

    @Test
    fun consentedScreenshotIsExpiringAndParentScoped() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "shot-${java.util.UUID.randomUUID()}@example.test")
        val otherToken = registerParent(client, "shot-other-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val requested = postJson(client, "/api/v1/devices/$deviceUuid/call-requests",
            """{"kind":"screenshot"}""", parentToken, HttpStatusCode.Created)
        val callId = json.parseToJsonElement(requested).jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/call-requests/$callId/respond",
            """{"accept":true}""", deviceToken)
        val jpeg = Base64.getEncoder().encodeToString(byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()
        ))
        val uploaded = postJson(client, "/api/v1/device/screenshots",
            """{"call_id":"$callId","image_base64":"$jpeg","width":1,"height":1}""",
            deviceToken, HttpStatusCode.Created)
        val screenshotId = json.parseToJsonElement(uploaded).jsonObject
            .getValue("id").jsonPrimitive.content

        val list = client.get("/api/v1/devices/$deviceUuid/screenshots") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.OK, list.status)
        assertEquals(screenshotId, json.parseToJsonElement(list.bodyAsText()).jsonArray
            .single().jsonObject.getValue("id").jsonPrimitive.content)
        val image = client.get("/api/v1/screenshots/$screenshotId") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.OK, image.status)
        assertEquals("image/jpeg", image.headers[HttpHeaders.ContentType])
        val foreign = client.get("/api/v1/screenshots/$screenshotId") {
            header(HttpHeaders.Authorization, "Bearer $otherToken")
        }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        val livekit = client.get("/api/v1/call-requests/$callId/token") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.Conflict, livekit.status)
    }

    @Test
    fun pairingCodeCannotBeReusedAndRevocationInvalidatesDeviceToken() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "revoke-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val claimBody = claim(client, parentToken, code)
        val deviceUuid = json.parseToJsonElement(claimBody).jsonObject.getValue("id").jsonPrimitive.content
        claim(client, parentToken, code, HttpStatusCode.NotFound)
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val revoked = client.delete("/api/v1/devices/$deviceUuid") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.NoContent, revoked.status)
        val me = client.get("/api/v1/device/me") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, me.status)
    }

    @Test
    fun usageIsScopedToThePairedParent() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "usage-${java.util.UUID.randomUUID()}@example.test")
        val otherToken = registerParent(client, "other-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)
        postJson(client, "/api/v1/device/usage",
            """{"day":"2026-11-12","app_minutes":{"com.example.game":37},"health":{"battery":88}}""",
            deviceToken, HttpStatusCode.NoContent)

        val usage = client.get("/api/v1/devices/$deviceUuid/usage?from=2026-11-12&to=2026-11-12") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.OK, usage.status)
        val entries = json.parseToJsonElement(usage.bodyAsText()).jsonArray
        assertEquals(1, entries.size)
        assertEquals(37, entries[0].jsonObject.getValue("app_minutes").jsonObject
            .getValue("com.example.game").jsonPrimitive.int)

        val foreign = client.get("/api/v1/devices/$deviceUuid/usage") {
            header(HttpHeaders.Authorization, "Bearer $otherToken")
        }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
    }

    @Test
    fun callsRequireExplicitChildConsentBeforeLiveKitToken() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "call-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val created = postJson(client, "/api/v1/devices/$deviceUuid/call-requests",
            """{"kind":"video"}""", parentToken, HttpStatusCode.Created)
        val callId = json.parseToJsonElement(created).jsonObject.getValue("id").jsonPrimitive.content

        val early = client.get("/api/v1/call-requests/$callId/token") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.Conflict, early.status)

        val pending = client.get("/api/v1/device/call-requests") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.OK, pending.status)
        assertEquals(1, json.parseToJsonElement(pending.bodyAsText()).jsonArray.size)
        postJson(client, "/api/v1/device/call-requests/$callId/respond",
            """{"accept":true}""", deviceToken)

        val accepted = client.get("/api/v1/call-requests/$callId/token") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.OK, accepted.status)
        val token = json.parseToJsonElement(accepted.bodyAsText()).jsonObject
            .getValue("token").jsonPrimitive.content
        val decoded = JWT.decode(token)
        assertEquals("test-api-key", decoded.issuer)
        assertNotNull(decoded.getClaim("video").asMap()["room"])
    }

    @Test
    fun endingAcceptedCallRevokesRoomAndInvalidatesBothTokens() = testApplication {
        val rooms = RecordingLiveKitRooms()
        val services = services(rooms)
        application { guardModule(services) }
        val parentToken = registerParent(client, "call-end-${java.util.UUID.randomUUID()}@example.test")
        val otherToken = registerParent(client, "call-end-other-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)
        val created = postJson(client, "/api/v1/devices/$deviceUuid/call-requests",
            """{"kind":"video"}""", parentToken, HttpStatusCode.Created)
        val callId = json.parseToJsonElement(created).jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/call-requests/$callId/respond",
            """{"accept":true}""", deviceToken)

        val unauthenticated = client.post("/api/v1/call-requests/$callId/end")
        assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)
        val foreign = client.post("/api/v1/call-requests/$callId/end") {
            header(HttpHeaders.Authorization, "Bearer $otherToken")
        }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
        assertTrue(rooms.deletedRooms.isEmpty())

        val ended = postJson(client, "/api/v1/call-requests/$callId/end", "{}", parentToken)
        val endedView = json.parseToJsonElement(ended).jsonObject
        assertEquals("ended", endedView.getValue("status").jsonPrimitive.content)
        assertNotNull(endedView.getValue("ended_at").jsonPrimitive.content)
        assertEquals(listOf(roomName(deviceUuid, callId)), rooms.deletedRooms)

        val parentTokenAfterEnd = client.get("/api/v1/call-requests/$callId/token") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.Conflict, parentTokenAfterEnd.status)
        val deviceTokenAfterEnd = client.get("/api/v1/device/call-requests/$callId/token") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.Conflict, deviceTokenAfterEnd.status)
        val deviceView = client.get("/api/v1/device/call-requests/$callId") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.OK, deviceView.status)
        assertEquals("ended", json.parseToJsonElement(deviceView.bodyAsText()).jsonObject
            .getValue("status").jsonPrimitive.content)

        val audit = client.get("/api/v1/devices/$deviceUuid/audit") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }.bodyAsText()
        assertTrue(audit.contains("call.ended"))
        val roomRevokedAt = services.database.withConnection { connection ->
            connection.prepareStatement(
                "SELECT room_revoked_at FROM call_requests WHERE id = ?"
            ).use { statement ->
                statement.setString(1, callId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    rows.getTimestamp("room_revoked_at")
                }
            }
        }
        assertNotNull(roomRevokedAt)

        postJson(client, "/api/v1/call-requests/$callId/end", "{}", parentToken)
        assertEquals(1, rooms.deletedRooms.size)
    }

    @Test
    fun failedRoomRevocationKeepsCallEndedAndCanBeRetried() = testApplication {
        val rooms = RecordingLiveKitRooms().apply { failDeletion = true }
        val services = services(rooms)
        application { guardModule(services) }
        val parentToken = registerParent(client, "call-retry-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)
        val created = postJson(client, "/api/v1/devices/$deviceUuid/call-requests",
            """{"kind":"screen"}""", parentToken, HttpStatusCode.Created)
        val callId = json.parseToJsonElement(created).jsonObject.getValue("id").jsonPrimitive.content
        val pendingEnd = postJson(client, "/api/v1/call-requests/$callId/end", "{}",
            parentToken, HttpStatusCode.Conflict)
        assertTrue(pendingEnd.contains("已接受"))
        postJson(client, "/api/v1/device/call-requests/$callId/respond",
            """{"accept":true}""", deviceToken)

        val failed = postJson(client, "/api/v1/call-requests/$callId/end", "{}",
            parentToken, HttpStatusCode.BadGateway)
        assertTrue(failed.contains("重试"))
        val list = client.get("/api/v1/devices/$deviceUuid/call-requests") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }.bodyAsText()
        assertEquals("ended", json.parseToJsonElement(list).jsonArray.single().jsonObject
            .getValue("status").jsonPrimitive.content)
        val tokenAfterFailure = client.get("/api/v1/call-requests/$callId/token") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.Conflict, tokenAfterFailure.status)
        val revokedBeforeRetry = services.database.withConnection { connection ->
            connection.prepareStatement(
                "SELECT room_revoked_at FROM call_requests WHERE id = ?"
            ).use { statement ->
                statement.setString(1, callId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    rows.getTimestamp("room_revoked_at")
                }
            }
        }
        assertEquals(null, revokedBeforeRetry)

        rooms.failDeletion = false
        postJson(client, "/api/v1/call-requests/$callId/end", "{}", parentToken)
        assertEquals(listOf(roomName(deviceUuid, callId)), rooms.deletedRooms)
        val audit = client.get("/api/v1/devices/$deviceUuid/audit") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }.bodyAsText()
        assertTrue(audit.contains("call.end_failed"))
        assertTrue(audit.contains("call.ended"))
    }

    @Test
    fun childCanEndAcceptedCallAndRevokeRoom() = testApplication {
        val rooms = RecordingLiveKitRooms()
        val services = services(rooms)
        application { guardModule(services) }
        val parentToken = registerParent(client, "call-child-end-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)
        val created = postJson(client, "/api/v1/devices/$deviceUuid/call-requests",
            """{"kind":"audio"}""", parentToken, HttpStatusCode.Created)
        val callId = json.parseToJsonElement(created).jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/call-requests/$callId/respond",
            """{"accept":true}""", deviceToken)
        val ended = postJson(client, "/api/v1/device/call-requests/$callId/end", "{}", deviceToken)
        assertEquals("ended", json.parseToJsonElement(ended).jsonObject
            .getValue("status").jsonPrimitive.content)
        assertEquals(listOf(roomName(deviceUuid, callId)), rooms.deletedRooms)
        val parentTokenAfterEnd = client.get("/api/v1/call-requests/$callId/token") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.Conflict, parentTokenAfterEnd.status)
        val audit = client.get("/api/v1/devices/$deviceUuid/audit") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }.bodyAsText()
        assertTrue(audit.contains("call.ended"))
    }

    @Test
    fun deviceCanRenewTokenWithKeystoreProofAndNonceCannotReplay() = testApplication {
        val services = services()
        application { guardModule(services) }
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        val pair = generator.generateKeyPair()
        val deviceId = "device-" + java.util.UUID.randomUUID()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        postJson(client, "/api/v1/device/register",
            """{"device_id":"$deviceId","public_key":"$publicKey","name":"Child tablet","platform":"android"}""",
            expected = HttpStatusCode.Created)

        val timestamp = System.currentTimeMillis()
        val nonce = java.util.UUID.randomUUID().toString()
        val message = "guard-device-token-v1\n$deviceId\n$timestamp\n$nonce"
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(message.toByteArray(Charsets.UTF_8))
        val signature = Base64.getEncoder().encodeToString(signer.sign())
        val body = """{"device_id":"$deviceId","timestamp":$timestamp,"nonce":"$nonce","signature":"$signature"}"""
        val renewed = postJson(client, "/api/v1/device/token", body)
        val token = json.parseToJsonElement(renewed).jsonObject
            .getValue("device_token").jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/device/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.status)

        postJson(client, "/api/v1/device/token", body,
            expected = HttpStatusCode.Unauthorized)
        postJson(client, "/api/v1/device/token", body.replace(nonce, "$nonce-x"),
            expected = HttpStatusCode.Unauthorized)
    }

    @Test
    fun signedQuizPacksAreVerifiedHostedAndSyncedToTheDevice() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "quiz-pack-${java.util.UUID.randomUUID()}@example.test")
        val otherToken = registerParent(client, "quiz-other-${java.util.UUID.randomUUID()}@example.test")
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        val pair = generator.generateKeyPair()
        val keyId = digestHex("SHA-256", pair.public.encoded)

        val uploaded = postJson(client, "/api/v1/quiz-packs", quizUpload(pair, 1),
            parentToken, HttpStatusCode.Created)
        val pack = json.parseToJsonElement(uploaded).jsonObject
        val packId = pack.getValue("id").jsonPrimitive.content
        assertEquals(1, pack.getValue("version").jsonPrimitive.int)
        assertEquals(4, pack.getValue("question_count").jsonPrimitive.int)
        assertEquals(keyId, pack.getValue("key_id").jsonPrimitive.content)
        assertTrue(pack.getValue("active").jsonPrimitive.content.toBoolean())

        val listed = client.get("/api/v1/quiz-packs") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.OK, listed.status)
        assertEquals(packId, json.parseToJsonElement(listed.bodyAsText()).jsonArray
            .single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/quiz-packs/$packId") {
            header(HttpHeaders.Authorization, "Bearer $otherToken")
        }.status)

        val (deviceToken, code, _) = registerDevice(client)
        val deviceUuid = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)
        val synced = client.get("/api/v1/device/quiz-pack") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.OK, synced.status)
        val syncedPack = json.parseToJsonElement(synced.bodyAsText()).jsonObject
        assertEquals(1, syncedPack.getValue("version").jsonPrimitive.int)
        assertEquals(keyId, syncedPack.getValue("key_id").jsonPrimitive.content)
        assertEquals(pack.getValue("envelope").jsonPrimitive.content,
            syncedPack.getValue("envelope").jsonPrimitive.content)

        postJson(client, "/api/v1/quiz-packs", quizUpload(pair, 2), parentToken,
            HttpStatusCode.Created)
        val newest = client.get("/api/v1/device/quiz-pack") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(2, json.parseToJsonElement(newest.bodyAsText()).jsonObject
            .getValue("version").jsonPrimitive.int)
        postJson(client, "/api/v1/quiz-packs", quizUpload(pair, 1),
            parentToken, HttpStatusCode.Conflict)
        postJson(client, "/api/v1/quiz-packs",
            quizUpload(pair, 3, signatureOverride = Base64.getEncoder().encodeToString(ByteArray(70))),
            parentToken, HttpStatusCode.BadRequest)

        postJson(client, "/api/v1/quiz-packs", quizUpload(pair, 9),
            parentToken, HttpStatusCode.Created)
        postJson(client, "/api/v1/quiz-packs", quizUpload(pair, 3),
            parentToken, HttpStatusCode.Conflict)
    }

    @Test
    fun signedQuizDeltaIsBoundToItsBaseAndServedOnlyForMatchingBaselines() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "quiz-delta-${java.util.UUID.randomUUID()}@example.test")
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        val pair = generator.generateKeyPair()
        postJson(client, "/api/v1/quiz-packs", quizUpload(pair, 1, questionPrompt = "7 + 5 = ?"),
            parentToken, HttpStatusCode.Created)

        val (deviceToken, code, _) = registerDevice(client)
        claim(client, parentToken, code)
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val operation = """[{"op":"put","question":{"id":"q1","type":"single_choice","prompt":"5 + 5 = ?","options":["10","12","13"],"answer_index":1,"subject":"mathematics","grade":3,"difficulty":"easy","language":"en","tags":["math","arithmetic"]}}]"""
        val uploaded = postJson(client, "/api/v1/quiz-packs",
            quizDeltaUpload(pair, 2, 1, operation), parentToken, HttpStatusCode.Created)
        val deltaPack = json.parseToJsonElement(uploaded).jsonObject
        assertEquals(1, deltaPack.getValue("base_version").jsonPrimitive.int)
        assertTrue(deltaPack.getValue("delta_envelope").jsonPrimitive.content.isNotEmpty())

        val deltaOnly = client.get("/api/v1/device/quiz-pack?base_version=1") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.OK, deltaOnly.status)
        val deltaView = json.parseToJsonElement(deltaOnly.bodyAsText()).jsonObject
        assertEquals(2, deltaView.getValue("version").jsonPrimitive.int)
        assertEquals(1, deltaView.getValue("base_version").jsonPrimitive.int)
        assertEquals("", deltaView.getValue("envelope").jsonPrimitive.content)
        assertTrue(deltaView.getValue("delta_envelope").jsonPrimitive.content.isNotEmpty())

        val mismatch = client.get("/api/v1/device/quiz-pack?base_version=4") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertTrue(json.parseToJsonElement(mismatch.bodyAsText()).jsonObject
            .getValue("envelope").jsonPrimitive.content.isNotEmpty())
        assertEquals(HttpStatusCode.BadRequest, client.get(
            "/api/v1/device/quiz-pack?base_version=not-a-number") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }.status)

        postJson(client, "/api/v1/quiz-packs",
            quizDeltaUpload(pair, 3, 99, operation), parentToken, HttpStatusCode.BadRequest)
        postJson(client, "/api/v1/quiz-packs",
            quizDeltaUpload(pair, 4, 2, operation, questionPrompt = "6 + 6 = ?"),
            parentToken, HttpStatusCode.BadRequest)
        postJson(client, "/api/v1/quiz-packs",
            quizDeltaUpload(pair, 5, 2, operation,
                deltaSignatureOverride = Base64.getEncoder().encodeToString(ByteArray(70))),
            parentToken, HttpStatusCode.BadRequest)
        postJson(client, "/api/v1/quiz-packs",
            quizDeltaUpload(pair, 6, 2, """[{"op":"remove","id":"missing"}]"""),
            parentToken, HttpStatusCode.BadRequest)

        val stillActive = client.get("/api/v1/device/quiz-pack") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(2, json.parseToJsonElement(stillActive.bodyAsText()).jsonObject
            .getValue("version").jsonPrimitive.int)
    }

    @Test
    fun quizKeyRotationRevocationAndTrustBoundariesAreEnforced() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client,
            "quiz-key-rotate-${java.util.UUID.randomUUID()}@example.test")
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        val keyA = generator.generateKeyPair()
        val keyB = generator.generateKeyPair()
        val keyC = generator.generateKeyPair()
        val keyIdA = digestHex("SHA-256", keyA.public.encoded)
        val keyIdB = digestHex("SHA-256", keyB.public.encoded)
        val keyIdC = digestHex("SHA-256", keyC.public.encoded)

        postJson(client, "/api/v1/quiz-packs", quizUpload(keyA, 1),
            parentToken, HttpStatusCode.Created)
        val rotateAtoB = quizKeyTransition(keyA, keyB)
        val created = json.parseToJsonElement(postJson(client,
            "/api/v1/quiz-keys/rotate",
            json.encodeToString(QuizKeyRotateRequest.serializer(), QuizKeyRotateRequest(rotateAtoB)),
            parentToken, HttpStatusCode.Created)).jsonObject
        val transitionBId = created.getValue("id").jsonPrimitive.content
        assertEquals(keyIdA, created.getValue("old_key_id").jsonPrimitive.content)
        assertEquals(keyIdB, created.getValue("new_key_id").jsonPrimitive.content)
        assertEquals(rotateAtoB, created.getValue("transition").jsonPrimitive.content)

        val duplicate = json.parseToJsonElement(postJson(client,
            "/api/v1/quiz-keys/rotate",
            json.encodeToString(QuizKeyRotateRequest.serializer(), QuizKeyRotateRequest(rotateAtoB)),
            parentToken, HttpStatusCode.Created)).jsonObject
        assertEquals(transitionBId, duplicate.getValue("id").jsonPrimitive.content)

        val (deviceToken, code, _) = registerDevice(client)
        claim(client, parentToken, code)
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)
        val deviceTransitions = client.get("/api/v1/device/quiz-key-transitions") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.OK, deviceTransitions.status)
        assertEquals(transitionBId, json.parseToJsonElement(deviceTransitions.bodyAsText())
            .jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)

        postJson(client, "/api/v1/quiz-packs", quizUpload(keyB, 2),
            parentToken, HttpStatusCode.Created)
        postJson(client, "/api/v1/quiz-keys/$transitionBId/revoke", "{}",
            parentToken, HttpStatusCode.Conflict)

        val rotateAtoC = quizKeyTransition(keyA, keyC)
        val transitionC = json.parseToJsonElement(postJson(client,
            "/api/v1/quiz-keys/rotate",
            json.encodeToString(QuizKeyRotateRequest.serializer(), QuizKeyRotateRequest(rotateAtoC)),
            parentToken, HttpStatusCode.Created)).jsonObject
        val transitionCId = transitionC.getValue("id").jsonPrimitive.content
        val revokedC = json.parseToJsonElement(postJson(client,
            "/api/v1/quiz-keys/$transitionCId/revoke", "{}", parentToken)).jsonObject
        assertTrue(revokedC.getValue("revoked_at") !is kotlinx.serialization.json.JsonNull)

        val unknownOldKey = quizKeyTransition(keyA, keyC,
            oldKeyId = digestHex("SHA-256", keyC.public.encoded))
        postJson(client, "/api/v1/quiz-keys/rotate",
            json.encodeToString(QuizKeyRotateRequest.serializer(),
                QuizKeyRotateRequest(unknownOldKey)),
            parentToken, HttpStatusCode.BadRequest)
        val wrongSignature = quizKeyTransition(keyA, keyC,
            signingPair = keyB, newKeyId = keyIdC)
        postJson(client, "/api/v1/quiz-keys/rotate",
            json.encodeToString(QuizKeyRotateRequest.serializer(),
                QuizKeyRotateRequest(wrongSignature)),
            parentToken, HttpStatusCode.BadRequest)
        val wrongNewFingerprint = quizKeyTransition(keyA, keyB,
            newKeyId = keyIdC)
        postJson(client, "/api/v1/quiz-keys/rotate",
            json.encodeToString(QuizKeyRotateRequest.serializer(),
                QuizKeyRotateRequest(wrongNewFingerprint)),
            parentToken, HttpStatusCode.BadRequest)

        val listed = client.get("/api/v1/quiz-keys") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }
        assertEquals(HttpStatusCode.OK, listed.status)
        val transitions = json.parseToJsonElement(listed.bodyAsText()).jsonArray
        assertEquals(2, transitions.size)
        assertEquals(transitionBId,
            transitions[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(transitionCId,
            transitions[1].jsonObject.getValue("id").jsonPrimitive.content)
        val unrevokedAt = transitions[0].jsonObject["revoked_at"]
        assertTrue(unrevokedAt == null || unrevokedAt is kotlinx.serialization.json.JsonNull)
        assertTrue(transitions[1].jsonObject.getValue("revoked_at") !is
            kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun helpRequestsAreIdempotentParentScopedAndAcknowledged() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client, "help-${java.util.UUID.randomUUID()}@example.test")
        val otherToken = registerParent(client, "help-other-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val claimed = json.parseToJsonElement(claim(client, parentToken, code)).jsonObject
        val deviceId = claimed.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val clientRequestId = "help-client-${java.util.UUID.randomUUID()}"
        val requestBody = """{"client_request_id":"$clientRequestId","target_package":"com.example.game","reason":"blocked","message":"I need help with my homework app"}"""
        val created = json.parseToJsonElement(postJson(client,
            "/api/v1/device/help-requests", requestBody,
            deviceToken, HttpStatusCode.Created)).jsonObject
        val requestId = created.getValue("id").jsonPrimitive.content
        assertEquals("pending", created.getValue("status").jsonPrimitive.content)

        val retried = json.parseToJsonElement(postJson(client,
            "/api/v1/device/help-requests", requestBody,
            deviceToken, HttpStatusCode.OK)).jsonObject
        assertEquals(requestId, retried.getValue("id").jsonPrimitive.content)
        val deduplicated = json.parseToJsonElement(postJson(client,
            "/api/v1/device/help-requests",
            requestBody.replace(clientRequestId, "help-client-${java.util.UUID.randomUUID()}"),
            deviceToken, HttpStatusCode.OK)).jsonObject
        assertEquals(requestId, deduplicated.getValue("id").jsonPrimitive.content)

        val deviceList = client.get("/api/v1/device/help-requests") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(1, json.parseToJsonElement(deviceList.bodyAsText()).jsonArray.size)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/devices/$deviceId/help-requests") {
            header(HttpHeaders.Authorization, "Bearer $otherToken")
        }.status)

        val replied = json.parseToJsonElement(postJson(client,
            "/api/v1/devices/$deviceId/help-requests/$requestId/respond",
            """{"reply":"Try the reading app now"}""", parentToken)).jsonObject
        assertEquals("answered", replied.getValue("status").jsonPrimitive.content)
        assertEquals("Try the reading app now", replied.getValue("parent_reply").jsonPrimitive.content)

        val deviceAfterReply = client.get("/api/v1/device/help-requests") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        val repliedView = json.parseToJsonElement(deviceAfterReply.bodyAsText()).jsonArray
            .single().jsonObject
        assertEquals("answered", repliedView.getValue("status").jsonPrimitive.content)
        assertEquals("Try the reading app now",
            repliedView.getValue("parent_reply").jsonPrimitive.content)

        val acknowledged = json.parseToJsonElement(postJson(client,
            "/api/v1/device/help-requests/$requestId/ack", "{}",
            deviceToken)).jsonObject
        assertEquals("acknowledged", acknowledged.getValue("status").jsonPrimitive.content)

        postJson(client, "/api/v1/devices/$deviceId/help-requests/$requestId/respond",
            """{"reply":"Thanks for reading it"}""", parentToken)
        val latest = client.get("/api/v1/device/help-requests") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals("Thanks for reading it", json.parseToJsonElement(latest.bodyAsText()).jsonArray
            .single().jsonObject.getValue("parent_reply").jsonPrimitive.content)
    }

    @Test
    fun accountDeletionRequiresExactReauthentication() = testApplication {
        val services = services()
        application { guardModule(services) }
        val email = "delete-check-${java.util.UUID.randomUUID()}@example.test"
        val token = registerParent(client, email)

        postJson(client, "/api/v1/me/delete",
            """{"email":"$email","password":"correct horse battery","confirmation":"DELETE"}""",
            expected = HttpStatusCode.Unauthorized)
        postJson(client, "/api/v1/me/delete",
            """{"email":"$email","password":"wrong password","confirmation":"DELETE"}""",
            token, HttpStatusCode.Forbidden)
        postJson(client, "/api/v1/me/delete",
            """{"email":"other-${java.util.UUID.randomUUID()}@example.test","password":"correct horse battery","confirmation":"DELETE"}""",
            token, HttpStatusCode.Forbidden)
        postJson(client, "/api/v1/me/delete",
            """{"email":"$email","password":"correct horse battery","confirmation":"delete"}""",
            token, HttpStatusCode.Forbidden)

        val me = client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(email, json.parseToJsonElement(me.bodyAsText()).jsonObject
            .getValue("email").jsonPrimitive.content)
    }

    @Test
    fun accountDeletionRemovesAllOwnedDataAndIsolatesOtherAccounts() = testApplication {
        val services = services()
        application { guardModule(services) }
        val targetEmail = "delete-target-${java.util.UUID.randomUUID()}@example.test"
        val otherEmail = "delete-other-${java.util.UUID.randomUUID()}@example.test"
        val targetToken = registerParent(client, targetEmail)
        val otherToken = registerParent(client, otherEmail)
        val targetParent = assertNotNull(services.parents.findByEmail(targetEmail))
        val otherParent = assertNotNull(services.parents.findByEmail(otherEmail))

        val (targetDeviceToken, targetCode, _) = registerDevice(client)
        val targetDeviceId = json.parseToJsonElement(claim(client, targetToken, targetCode))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", targetDeviceToken)
        val (otherDeviceToken, otherCode, _) = registerDevice(client)
        val otherDeviceId = json.parseToJsonElement(claim(client, otherToken, otherCode))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", otherDeviceToken)

        seedAccountData(services, targetParent.id, targetDeviceId)
        seedAccountData(services, otherParent.id, otherDeviceId)
        assertAccountData(services, targetParent.id, targetDeviceId, 1, atLeast = true)
        assertAccountData(services, otherParent.id, otherDeviceId, 1, atLeast = true)

        postJson(client, "/api/v1/me/delete",
            """{"email":"$targetEmail","password":"correct horse battery","confirmation":"DELETE"}""",
            targetToken, HttpStatusCode.NoContent)

        assertAccountData(services, targetParent.id, targetDeviceId, 0)
        assertAccountData(services, otherParent.id, otherDeviceId, 1, atLeast = true)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer $targetToken")
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/devices") {
            header(HttpHeaders.Authorization, "Bearer $targetToken")
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/device/me") {
            header(HttpHeaders.Authorization, "Bearer $targetDeviceToken")
        }.status)
        postJson(client, "/api/v1/auth/login",
            """{"email":"$targetEmail","password":"correct horse battery"}""",
            expected = HttpStatusCode.Unauthorized)

        val otherDevices = client.get("/api/v1/devices") {
            header(HttpHeaders.Authorization, "Bearer $otherToken")
        }
        assertEquals(HttpStatusCode.OK, otherDevices.status)
        assertEquals(otherDeviceId, json.parseToJsonElement(otherDevices.bodyAsText())
            .jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/device/me") {
            header(HttpHeaders.Authorization, "Bearer $otherDeviceToken")
        }.status)
    }

    @Test
    fun parentSessionsCanBeListedAndRevokedIndividually() = testApplication {
        val services = services()
        application { guardModule(services) }
        val email = "sessions-${java.util.UUID.randomUUID()}@example.test"
        val firstToken = registerParent(client, email)
        val secondToken = json.parseToJsonElement(postJson(client, "/api/v1/auth/login",
            """{"email":"$email","password":"correct horse battery"}"""))
            .jsonObject.getValue("token").jsonPrimitive.content

        val listed = client.get("/api/v1/me/sessions") {
            header(HttpHeaders.Authorization, "Bearer $firstToken")
        }
        assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
        val sessions = json.parseToJsonElement(listed.bodyAsText()).jsonArray
        assertEquals(2, sessions.size)
        assertEquals(1, sessions.count { it.jsonObject.getValue("current").jsonPrimitive.content == "true" })
        assertEquals(2, sessions.count { it.jsonObject.getValue("status").jsonPrimitive.content == "active" })
        val otherSessionId = sessions.single {
            it.jsonObject.getValue("current").jsonPrimitive.content == "false"
        }.jsonObject.getValue("id").jsonPrimitive.content

        val revoked = postJson(client, "/api/v1/me/sessions/$otherSessionId/revoke", "{}", firstToken)
        assertEquals("revoked", json.parseToJsonElement(revoked).jsonObject
            .getValue("status").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer $secondToken")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer $firstToken")
        }.status)

        val currentSessionId = sessions.single {
            it.jsonObject.getValue("current").jsonPrimitive.content == "true"
        }.jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/me/sessions/$currentSessionId/revoke", "{}", firstToken)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer $firstToken")
        }.status)
    }

    @Test
    fun parentSessionsAreIsolatedAndOtherSessionsCanBeRevokedTogether() = testApplication {
        val services = services()
        application { guardModule(services) }
        val email = "sessions-bulk-${java.util.UUID.randomUUID()}@example.test"
        val otherEmail = "sessions-other-${java.util.UUID.randomUUID()}@example.test"
        val currentToken = registerParent(client, email)
        val secondToken = json.parseToJsonElement(postJson(client, "/api/v1/auth/login",
            """{"email":"$email","password":"correct horse battery"}"""))
            .jsonObject.getValue("token").jsonPrimitive.content
        val otherToken = registerParent(client, otherEmail)

        val sessions = json.parseToJsonElement(client.get("/api/v1/me/sessions") {
            header(HttpHeaders.Authorization, "Bearer $currentToken")
        }.bodyAsText()).jsonArray
        val secondSessionId = sessions.single {
            it.jsonObject.getValue("current").jsonPrimitive.content == "false"
        }.jsonObject.getValue("id").jsonPrimitive.content

        postJson(client, "/api/v1/me/sessions/$secondSessionId/revoke", "{}", otherToken,
            HttpStatusCode.NotFound)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer $secondToken")
        }.status)

        val revoked = json.parseToJsonElement(postJson(client,
            "/api/v1/me/sessions/revoke-others", "{}", currentToken)).jsonObject
        assertEquals(1, revoked.getValue("revoked").jsonPrimitive.int)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer $secondToken")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") {
            header(HttpHeaders.Authorization, "Bearer $currentToken")
        }.status)
    }

    @Test
    fun policyHistoryCanBeRolledBackWithANewIncreasingRevision() = testApplication {
        val services = services()
        application { guardModule(services) }
        val parentToken = registerParent(client,
            "policy-history-${java.util.UUID.randomUUID()}@example.test")
        val (deviceToken, code, _) = registerDevice(client)
        val deviceId = json.parseToJsonElement(claim(client, parentToken, code))
            .jsonObject.getValue("id").jsonPrimitive.content
        postJson(client, "/api/v1/device/confirm-pairing", "{}", deviceToken)

        val first = json.parseToJsonElement(putJson(client,
            "/api/v1/devices/$deviceId/policy",
            """{"policy":${policy("{\"com.example.game\":60}")}}""", parentToken)).jsonObject
        val second = json.parseToJsonElement(putJson(client,
            "/api/v1/devices/$deviceId/policy",
            """{"policy":${policy("{\"com.example.game\":30}")}}""", parentToken)).jsonObject
        assertEquals(1, first.getValue("revision").jsonPrimitive.int)
        assertEquals(2, second.getValue("revision").jsonPrimitive.int)

        val history = json.parseToJsonElement(client.get(
            "/api/v1/devices/$deviceId/policy/history"
        ) {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }.bodyAsText()).jsonArray
        assertEquals(listOf(2, 1), history.map {
            it.jsonObject.getValue("revision").jsonPrimitive.int
        })

        val rolledBack = json.parseToJsonElement(postJson(client,
            "/api/v1/devices/$deviceId/policy/1/rollback", "{}", parentToken)).jsonObject
        assertEquals(3, rolledBack.getValue("revision").jsonPrimitive.int)
        val restored = json.parseToJsonElement(
            rolledBack.getValue("payload").jsonPrimitive.content
        ).jsonObject
        assertEquals(3, restored.getValue("revision").jsonPrimitive.int)
        assertEquals(60, restored.getValue("daily_budgets").jsonObject
            .getValue("com.example.game").jsonPrimitive.int)

        val audit = json.parseToJsonElement(client.get("/api/v1/devices/$deviceId/audit") {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }.bodyAsText()).jsonArray
        assertTrue(audit.any { it.jsonObject.getValue("event").jsonPrimitive.content == "policy.rolled_back" })
        assertEquals(HttpStatusCode.NotFound, client.post(
            "/api/v1/devices/$deviceId/policy/99/rollback"
        ) {
            header(HttpHeaders.Authorization, "Bearer $parentToken")
        }.status)
    }

    @Test
    fun retentionCleanupRemovesExpiredScreenshotsCallsAndRetiredSessions() {
        val services = services()
        val parent = services.parents.create(
            "retention-${java.util.UUID.randomUUID()}@example.test", "hash"
        )
        val session = services.parentSessions.create(parent.id, 3600)
        assertNotNull(services.parentSessions.revoke(parent.id, session.id))
        val device = services.devices.register(
            DeviceRegisterRequest("retention-device-${java.util.UUID.randomUUID()}",
                deviceKeyPair().first, "Retention device", "android"),
            "pairing-hash", Instant.now().plusSeconds(600)
        )
        services.screenshots.save(device.id, "retention-call", byteArrayOf(1, 2, 3), 1, 1)
        val pendingCall = services.calls.create(device.id, "audio", 60)
        services.database.withConnection { connection ->
            connection.prepareStatement("UPDATE screenshots SET expires_at = ?").use { statement ->
                statement.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(1)))
                statement.executeUpdate()
            }
            connection.prepareStatement("UPDATE call_requests SET expires_at = ? WHERE id = ?").use { statement ->
                statement.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(1)))
                statement.setString(2, pendingCall.id)
                statement.executeUpdate()
            }
            connection.prepareStatement("UPDATE parent_sessions SET revoked_at = ?").use { statement ->
                statement.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(31L * 24 * 60 * 60)))
                statement.executeUpdate()
            }
        }

        assertEquals(1, services.screenshots.cleanupExpired())
        assertEquals(1, services.calls.expireStale())
        assertEquals(1, services.parentSessions.cleanupRetired())
        assertEquals(0, services.screenshots.list(device.id).size)
        assertEquals("expired", services.calls.find(pendingCall.id)?.status)
        assertEquals(0, services.parentSessions.list(parent.id).size)
    }

    @Test
    fun missingAuthenticationIsRejected() = testApplication {
        val services = services()
        application { guardModule(services) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/devices").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/device/me").status)
    }

    @Test
    fun corsAllowsConfiguredBrowserOriginOnJsonPost() = testApplication {
        val services = services()
        application { guardModule(services) }
        val origin = "http://localhost:5173"
        val response = client.post("/api/v1/auth/register") {
            header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.Json)
            setBody("""{"email":"cors-${java.util.UUID.randomUUID()}@example.test",
                "password":"cors-password-123"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertEquals(origin, response.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}

package com.guard.server

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
import kotlinx.serialization.json.long
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuizUpstreamTest {
    private val json = Json { ignoreUnknownKeys = true }

    private data class RegisteredParent(val token: String, val id: String)

    private class FakeUpstreamFetcher(
        private val responses: MutableList<UpstreamHttpResponse>
    ) : QuizUpstreamFetcher {
        val sources = mutableListOf<QuizUpstreamRow>()

        override fun fetch(source: QuizUpstreamRow): UpstreamHttpResponse {
            sources += source
            check(responses.isNotEmpty()) { "No fake upstream response remains" }
            return responses.removeAt(0)
        }
    }

    private fun services(fetcher: QuizUpstreamFetcher): Services {
        val name = "guard-upstream-" + java.util.UUID.randomUUID()
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
        return Services(config, database, fetcher)
    }

    private fun keyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        return generator.generateKeyPair()
    }

    private fun quizPayload(version: Long, prompt: String): String = """{
        "schema":1,"version":$version,"published_at":"2026-11-13T00:00:00Z","questions":[
          {"id":"q1","type":"single_choice","prompt":"$prompt","options":["10","12","13"],"answer_index":1,
           "subject":"mathematics","grade":3,"difficulty":"easy","language":"en","tags":["math"]}
        ]
    }"""

    private fun quizEnvelope(
        pair: KeyPair,
        version: Long,
        prompt: String = "7 + 5 = ?",
        signatureOverride: String? = null
    ): String {
        val payload = quizPayload(version, prompt)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        val signature = signatureOverride ?: Base64.getEncoder().encodeToString(signer.sign())
        return JsonObject(mapOf(
            "schema" to JsonPrimitive(1),
            "key_id" to JsonPrimitive(digestHex("SHA-256", pair.public.encoded)),
            "payload" to JsonPrimitive(payload),
            "signature" to JsonPrimitive(signature)
        )).toString()
    }

    private fun quizDeltaEnvelope(
        pair: KeyPair,
        version: Long,
        baseVersion: Long,
        prompt: String
    ): String {
        val payload = """{"schema":2,"version":$version,"base_version":$baseVersion,"published_at":"2026-11-13T00:00:00Z","operations":[{"op":"put","question":{"id":"q1","type":"single_choice","prompt":"$prompt","options":["10","12","13"],"answer_index":1,"subject":"mathematics","grade":3,"difficulty":"easy","language":"en","tags":["math"]}}]}"""
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        return JsonObject(mapOf(
            "schema" to JsonPrimitive(1),
            "key_id" to JsonPrimitive(digestHex("SHA-256", pair.public.encoded)),
            "payload" to JsonPrimitive(payload),
            "signature" to JsonPrimitive(Base64.getEncoder().encodeToString(signer.sign()))
        )).toString()
    }

    private fun deltaBundle(
        fullEnvelope: String,
        deltaEnvelope: String,
        baseVersion: Long
    ): String = JsonObject(mapOf(
        "envelope" to JsonPrimitive(fullEnvelope),
        "delta_envelope" to JsonPrimitive(deltaEnvelope),
        "base_version" to JsonPrimitive(baseVersion)
    )).toString()

    private fun url(host: String): String =
        listOf("ht" + "tps:", "", host, "quiz.json").joinToString("/")

    private fun upstreamConfig(pair: KeyPair): String = JsonObject(mapOf(
        "title" to JsonPrimitive("Trusted practice feed"),
        "url" to JsonPrimitive(url("updates.example.test")),
        "public_key" to JsonPrimitive(Base64.getEncoder().encodeToString(pair.public.encoded)),
        "interval_minutes" to JsonPrimitive(60),
        "enabled" to JsonPrimitive(true)
    )).toString()

    private suspend fun postJson(
        client: io.ktor.client.HttpClient,
        path: String,
        body: String,
        token: String,
        expected: HttpStatusCode = HttpStatusCode.OK
    ): JsonObject {
        val response = client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(expected, response.status, response.bodyAsText())
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
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

    private suspend fun registerParent(
        client: io.ktor.client.HttpClient,
        email: String
    ): RegisteredParent {
        val response = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"correct horse battery"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return RegisteredParent(
            body.getValue("token").jsonPrimitive.content,
            body.getValue("parent").jsonObject.getValue("id").jsonPrimitive.content
        )
    }

    private suspend fun sync(
        client: io.ktor.client.HttpClient,
        token: String
    ): JsonObject = postJson(client, "/api/v1/quiz-upstream/sync", "{}", token)

    @Test
    fun upstreamUrlPolicyRejectsUnsafeEndpointsAndAcceptsPublicHttps() {
        val insecure = listOf("ht" + "tp:", "", "updates.example.test", "quiz.json")
            .joinToString("/")
        assertFailsWith<QuizUpstreamException> {
            QuizUpstreamUrlPolicy.validateForStorage(insecure)
        }
        assertFailsWith<QuizUpstreamException> {
            QuizUpstreamUrlPolicy.validateForStorage(
                url("user:secret@updates.example.test")
            )
        }
        assertFailsWith<QuizUpstreamException> {
            QuizUpstreamUrlPolicy.validateForStorage(
                url("updates.example.test") + "#fragment"
            )
        }
        listOf(
            "127.0.0.1",
            "10.20.30.40",
            "172.16.0.1",
            "192.168.1.1",
            "169.254.10.20",
            "100.64.0.1",
            "[::1]",
            "[fd00::1]"
        ).forEach { host ->
            assertFailsWith<QuizUpstreamException>(host) {
                QuizUpstreamUrlPolicy.validateForFetch(url(host))
            }
        }
        assertTrue(QuizUpstreamUrlPolicy.isPublicAddress(
            InetAddress.getByName("93.184.216.34")
        ))
        assertTrue(QuizUpstreamUrlPolicy.isPublicAddress(
            InetAddress.getByName("2606:4700:4700::1111")
        ))
        assertFalse(QuizUpstreamUrlPolicy.isPublicAddress(
            InetAddress.getByName("198.51.100.5")
        ))
        assertEquals("93.184.216.34",
            QuizUpstreamUrlPolicy.validateForFetch(url("93.184.216.34")).host)
    }

    @Test
    fun upstreamConfigurationValidatesBeforeStorage() = testApplication {
        val services = services(FakeUpstreamFetcher(mutableListOf()))
        application { guardModule(services) }
        val parent = registerParent(
            client, "upstream-validation-${java.util.UUID.randomUUID()}@example.test"
        )
        val pair = keyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)

        fun config(
            title: String = "Trusted feed",
            endpoint: String = url("updates.example.test"),
            key: String = publicKey,
            interval: Int = 60
        ) = JsonObject(mapOf(
            "title" to JsonPrimitive(title),
            "url" to JsonPrimitive(endpoint),
            "public_key" to JsonPrimitive(key),
            "interval_minutes" to JsonPrimitive(interval),
            "enabled" to JsonPrimitive(true)
        )).toString()

        val insecure = listOf("ht" + "tp:", "", "updates.example.test", "quiz.json")
            .joinToString("/")
        putJson(client, "/api/v1/quiz-upstream", config(title = "   "), parent.token,
            HttpStatusCode.BadRequest)
        putJson(client, "/api/v1/quiz-upstream", config(endpoint = insecure), parent.token,
            HttpStatusCode.BadRequest)
        putJson(client, "/api/v1/quiz-upstream",
            config(endpoint = url("updates.example.test") + "#fragment"), parent.token,
            HttpStatusCode.BadRequest)
        putJson(client, "/api/v1/quiz-upstream", config(interval = 14), parent.token,
            HttpStatusCode.BadRequest)
        putJson(client, "/api/v1/quiz-upstream", config(key = "not-a-public-key"), parent.token,
            HttpStatusCode.BadRequest)

        val empty = client.get("/api/v1/quiz-upstream") {
            header(HttpHeaders.Authorization, "Bearer ${parent.token}")
        }
        assertEquals(HttpStatusCode.NoContent, empty.status)
    }

    @Test
    fun dueSourcesExcludeRecentlyCheckedRowsWithoutStarvingOthers() {
        val services = services(FakeUpstreamFetcher(mutableListOf()))
        val pair = keyPair()
        val key = QuizPackValidator.inspectPublicKey(
            Base64.getEncoder().encodeToString(pair.public.encoded)
        )
        val first = services.parents.create(
            "due-first-${java.util.UUID.randomUUID()}@example.test",
            services.passwordHasher.hash("correct horse battery")
        )
        val second = services.parents.create(
            "due-second-${java.util.UUID.randomUUID()}@example.test",
            services.passwordHasher.hash("correct horse battery")
        )
        services.quizUpstreams.upsert(
            first.id, "First", url("first.example.test"), key, 60, true
        )
        services.quizUpstreams.upsert(
            second.id, "Second", url("second.example.test"), key, 60, true
        )

        val now = Instant.now()
        assertEquals(2, services.quizUpstreams.due(now).size)
        services.quizUpstreams.recordSuccess(first.id, now, "not_modified", null, null, null)
        assertEquals(
            setOf(second.id),
            services.quizUpstreams.due(now.plusSeconds(60)).map { it.parentId }.toSet()
        )
        assertTrue(services.quizUpstreams.due(now.plusSeconds(61 * 60))
            .any { it.parentId == first.id })
    }

    @Test
    fun signedUpstreamSyncIsConditionalVersionedAndFailClosed() = testApplication {
        val pair = keyPair()
        val envelope2 = quizEnvelope(pair, 2)
        val envelope2Again = quizEnvelope(pair, 2, prompt = "Different but same version")
        val envelope1 = quizEnvelope(pair, 1)
        val badEnvelope3 = quizEnvelope(
            pair, 3,
            signatureOverride = Base64.getEncoder().encodeToString(ByteArray(70))
        )
        val envelope3 = quizEnvelope(pair, 3, prompt = "8 + 4 = ?")
        val fetcher = FakeUpstreamFetcher(mutableListOf(
            UpstreamHttpResponse(200, envelope2, "etag-2", "Wed, 12 Nov 2026 10:00:00 GMT"),
            UpstreamHttpResponse(304, null, "etag-2", "Wed, 12 Nov 2026 10:00:00 GMT"),
            UpstreamHttpResponse(200, envelope2Again, "etag-2b", null),
            UpstreamHttpResponse(200, envelope1, "etag-1", null),
            UpstreamHttpResponse(200, badEnvelope3, "etag-3-bad", null),
            UpstreamHttpResponse(200, envelope3, "etag-3", null)
        ))
        val services = services(fetcher)
        application { guardModule(services) }
        val parent = registerParent(
            client, "upstream-sync-${java.util.UUID.randomUUID()}@example.test"
        )
        val other = registerParent(
            client, "upstream-other-${java.util.UUID.randomUUID()}@example.test"
        )

        val stored = json.parseToJsonElement(putJson(
            client, "/api/v1/quiz-upstream", upstreamConfig(pair), parent.token
        )).jsonObject
        assertEquals("never", stored.getValue("last_status").jsonPrimitive.content)
        assertEquals(60, stored.getValue("interval_minutes").jsonPrimitive.int)
        assertEquals(
            digestHex("SHA-256", pair.public.encoded),
            stored.getValue("key_id").jsonPrimitive.content
        )

        val otherRead = client.get("/api/v1/quiz-upstream") {
            header(HttpHeaders.Authorization, "Bearer ${other.token}")
        }
        assertEquals(HttpStatusCode.NoContent, otherRead.status)

        val first = sync(client, parent.token)
        assertEquals("updated", first.getValue("status").jsonPrimitive.content)
        assertEquals(2, first.getValue("version").jsonPrimitive.long)
        val firstActive = assertNotNull(services.quizPacks.findActive(parent.id))
        assertEquals(envelope2, firstActive.envelope)

        val notModified = sync(client, parent.token)
        assertEquals("not_modified", notModified.getValue("status").jsonPrimitive.content)
        assertEquals("etag-2", fetcher.sources[1].etag)
        assertEquals("Wed, 12 Nov 2026 10:00:00 GMT", fetcher.sources[1].lastModified)
        assertEquals(2, assertNotNull(services.quizPacks.findActive(parent.id)).version)

        val noUpdate = sync(client, parent.token)
        assertEquals("no_update", noUpdate.getValue("status").jsonPrimitive.content)
        assertEquals(envelope2, assertNotNull(services.quizPacks.findActive(parent.id)).envelope)

        val regression = sync(client, parent.token)
        assertEquals("failed", regression.getValue("status").jsonPrimitive.content)
        assertTrue(regression.getValue("message").jsonPrimitive.content
            .contains("version-regression"))
        assertEquals(2, assertNotNull(services.quizPacks.findActive(parent.id)).version)

        val invalidSignature = sync(client, parent.token)
        assertEquals("failed", invalidSignature.getValue("status").jsonPrimitive.content)
        assertTrue(invalidSignature.getValue("message").jsonPrimitive.content
            .contains("invalid-pack"))
        assertEquals(envelope2, assertNotNull(services.quizPacks.findActive(parent.id)).envelope)

        val updated = sync(client, parent.token)
        assertEquals("updated", updated.getValue("status").jsonPrimitive.content)
        val active = assertNotNull(services.quizPacks.findActive(parent.id))
        assertEquals(3, active.version)
        assertEquals(envelope3, active.envelope)

        val current = client.get("/api/v1/quiz-upstream") {
            header(HttpHeaders.Authorization, "Bearer ${parent.token}")
        }
        assertEquals(HttpStatusCode.OK, current.status)
        val currentBody = json.parseToJsonElement(current.bodyAsText()).jsonObject
        assertEquals("updated", currentBody.getValue("last_status").jsonPrimitive.content)
        assertEquals(3, currentBody.getValue("last_version").jsonPrimitive.long)
        assertEquals("", currentBody.getValue("last_error").jsonPrimitive.content)

        val eventsResponse = client.get("/api/v1/quiz-upstream/events") {
            header(HttpHeaders.Authorization, "Bearer ${parent.token}")
        }
        assertEquals(HttpStatusCode.OK, eventsResponse.status)
        val eventNames = json.parseToJsonElement(eventsResponse.bodyAsText()).jsonArray
            .map { it.jsonObject.getValue("event").jsonPrimitive.content }
        assertTrue("upstream.sync.updated-full" in eventNames)
        assertTrue("upstream.sync.not_modified" in eventNames)
        assertTrue("upstream.sync.no_update" in eventNames)
        assertTrue("upstream.sync.failed" in eventNames)

        val deleted = client.delete("/api/v1/quiz-upstream") {
            header(HttpHeaders.Authorization, "Bearer ${parent.token}")
        }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        val afterDelete = client.get("/api/v1/quiz-upstream") {
            header(HttpHeaders.Authorization, "Bearer ${parent.token}")
        }
        assertEquals(HttpStatusCode.NoContent, afterDelete.status)
    }

    @Test
    fun upstreamDeltaBundleAppliesOnlyToMatchingBasesAndFallsBackSafely() = testApplication {
        val pair = keyPair()
        val otherPair = keyPair()
        val full1 = quizEnvelope(pair, 1)
        val full2 = quizEnvelope(pair, 2, prompt = "2 + 2 = ?")
        val delta2 = quizDeltaEnvelope(pair, 2, 1, "2 + 2 = ?")
        val full3 = quizEnvelope(pair, 3, prompt = "3 + 3 = ?")
        val delta3WrongBase = quizDeltaEnvelope(pair, 3, 1, "3 + 3 = ?")
        val full4 = quizEnvelope(pair, 4, prompt = "4 + 4 = ?")
        val badDelta4 = quizDeltaEnvelope(otherPair, 4, 3, "4 + 4 = ?")
        val standaloneDelta5 = quizDeltaEnvelope(pair, 5, 4, "5 + 5 = ?")
        val fetcher = FakeUpstreamFetcher(mutableListOf(
            UpstreamHttpResponse(200, full1, "e1", null),
            UpstreamHttpResponse(200, deltaBundle(full2, delta2, 1), "e2", null),
            UpstreamHttpResponse(200, deltaBundle(full3, delta3WrongBase, 1), "e3", null),
            UpstreamHttpResponse(200, deltaBundle(full4, badDelta4, 3), "e4", null),
            UpstreamHttpResponse(200, standaloneDelta5, "e5", null)
        ))
        val services = services(fetcher)
        application { guardModule(services) }
        val parent = registerParent(
            client, "upstream-delta-${java.util.UUID.randomUUID()}@example.test"
        )
        putJson(client, "/api/v1/quiz-upstream", upstreamConfig(pair), parent.token)

        assertEquals("updated", sync(client, parent.token)
            .getValue("status").jsonPrimitive.content)
        assertEquals("updated", sync(client, parent.token)
            .getValue("status").jsonPrimitive.content)
        val deltaActive = assertNotNull(services.quizPacks.findActive(parent.id))
        assertEquals(2, deltaActive.version)
        assertNotNull(deltaActive.deltaEnvelope)
        assertEquals(1, deltaActive.deltaBaseVersion)

        val mismatch = sync(client, parent.token)
        assertEquals("updated", mismatch.getValue("status").jsonPrimitive.content)
        val mismatchActive = assertNotNull(services.quizPacks.findActive(parent.id))
        assertEquals(3, mismatchActive.version)
        assertEquals(null, mismatchActive.deltaEnvelope)

        val invalid = sync(client, parent.token)
        assertEquals("updated", invalid.getValue("status").jsonPrimitive.content)
        val invalidActive = assertNotNull(services.quizPacks.findActive(parent.id))
        assertEquals(4, invalidActive.version)
        assertEquals(null, invalidActive.deltaEnvelope)

        val standalone = sync(client, parent.token)
        assertEquals("failed", standalone.getValue("status").jsonPrimitive.content)
        assertTrue(standalone.getValue("message").jsonPrimitive.content
            .contains("delta-full-target-required"))
        assertEquals(4, assertNotNull(services.quizPacks.findActive(parent.id)).version)

        val eventsResponse = client.get("/api/v1/quiz-upstream/events") {
            header(HttpHeaders.Authorization, "Bearer ${parent.token}")
        }
        val eventNames = json.parseToJsonElement(eventsResponse.bodyAsText()).jsonArray
            .map { it.jsonObject.getValue("event").jsonPrimitive.content }
        assertTrue("upstream.sync.updated-delta" in eventNames)
        assertTrue("upstream.sync.delta-base-mismatch" in eventNames)
        assertTrue("upstream.sync.delta-invalid" in eventNames)
    }
}

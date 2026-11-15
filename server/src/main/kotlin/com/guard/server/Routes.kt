package com.guard.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

fun Application.configureRoutes(services: Services) {
    routing {
        get("/healthz") {
            val healthy = services.database.healthy()
            call.respond(
                if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                HealthResponse(if (healthy) "ok" else "degraded", healthy, "0.1.0")
            )
        }
        get("/api/v1/health") {
            val healthy = services.database.healthy()
            call.respond(
                if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                HealthResponse(if (healthy) "ok" else "degraded", healthy, "0.1.0")
            )
        }
        get("/api/v1/config/policy-key") {
            call.respond(PolicyKeyResponse(services.config.policySigner.keyId,
                services.config.policySigner.publicKeyBase64))
        }

        route("/api/v1/quiz-packs") {
            get {
                val parentId = call.requireParent(services)
                call.respond(services.quizPacks.list(parentId).map(::quizSummary))
            }
            post {
                val parentId = call.requireParent(services)
                call.limit(services.quizPackLimiter, "quiz-pack-upload")
                val body = call.receive<QuizPackCreateRequest>()
                val title = body.title.filterNot { it.isISOControl() }.trim()
                if (title.length !in 1..120) badRequest("Quiz pack title must be 1 to 120 characters")
                val verified = QuizPackValidator.verify(body.public_key, body.envelope)
                val delta = if (body.delta_envelope.isNullOrBlank()) {
                    null
                } else {
                    val baseVersion = body.base_version
                        ?: badRequest("base_version is required with delta_envelope")
                    val base = services.quizPacks.findVersion(parentId, baseVersion)
                        ?: badRequest("The delta base version does not exist")
                    if (!base.publicKey.equals(verified.normalizedPublicKey, ignoreCase = true)) {
                        badRequest("A delta must be signed by the same key as its base pack")
                    }
                    QuizPackValidator.verifyDelta(body.public_key, body.delta_envelope,
                        base.envelope, verified.envelope, verified.version)
                }
                val row = services.quizPacks.create(parentId, title, verified, delta, body.activate)
                call.respond(HttpStatusCode.Created, quizDetail(row))
            }
            get("/{id}") {
                val parentId = call.requireParent(services)
                val id = call.parameters["id"] ?: notFound()
                val row = services.quizPacks.find(parentId, id) ?: notFound("Quiz pack not found")
                call.respond(quizDetail(row))
            }
            post("/{id}/activate") {
                val parentId = call.requireParent(services)
                val id = call.parameters["id"] ?: notFound()
                val row = services.quizPacks.activate(parentId, id)
                    ?: notFound("Quiz pack not found")
                call.respond(quizDetail(row))
            }
        }

        route("/api/v1/quiz-upstream") {
            get {
                val parentId = call.requireParent(services)
                val row = services.quizUpstreams.find(parentId)
                if (row == null) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(quizUpstreamView(row))
                }
            }
            put {
                val parentId = call.requireParent(services)
                call.limit(services.quizUpstreamLimiter, "quiz-upstream-save:$parentId")
                val body = call.receive<QuizUpstreamUpsertRequest>()
                val title = body.title.filterNot { it.isISOControl() }.trim()
                if (title.length !in 1..120) {
                    badRequest("Upstream title must be 1 to 120 characters")
                }
                if (body.intervalMinutes !in 15..1_440) {
                    badRequest("Upstream sync interval must be between 15 and 1440 minutes")
                }
                val uri = try {
                    QuizUpstreamUrlPolicy.validateForStorage(body.url)
                } catch (exception: QuizUpstreamException) {
                    badRequest(exception.code)
                }
                val key = QuizPackValidator.inspectPublicKey(body.publicKey)
                val existed = services.quizUpstreams.find(parentId) != null
                val row = services.quizUpstreams.upsert(
                    parentId, title, uri.toString(), key, body.intervalMinutes, body.enabled
                )
                services.quizUpstreams.appendEvent(
                    parentId,
                    if (existed) "upstream.updated" else "upstream.configured",
                    title
                )
                call.respond(quizUpstreamView(row))
            }
            delete {
                val parentId = call.requireParent(services)
                call.limit(services.quizUpstreamLimiter, "quiz-upstream-delete:$parentId")
                if (services.quizUpstreams.delete(parentId)) {
                    services.quizUpstreams.appendEvent(parentId, "upstream.deleted")
                }
                call.respond(HttpStatusCode.NoContent)
            }
            post("/sync") {
                val parentId = call.requireParent(services)
                call.limit(services.quizUpstreamLimiter, "quiz-upstream-sync:$parentId")
                val result = withContext(Dispatchers.IO) {
                    services.quizUpstreamSync.sync(parentId)
                }
                call.respond(result)
            }
            get("/events") {
                val parentId = call.requireParent(services)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                if (limit !in 1..100) badRequest("Event limit must be between 1 and 100")
                call.respond(services.quizUpstreams.events(parentId, limit))
            }
        }

        route("/api/v1/quiz-keys") {
            get {
                val parentId = call.requireParent(services)
                call.respond(services.quizKeyTransitions.list(parentId)
                    .map(::quizKeyTransitionView))
            }
            post("/rotate") {
                val parentId = call.requireParent(services)
                call.limit(services.quizKeyLimiter, "quiz-key-rotate:$parentId")
                val body = call.receive<QuizKeyRotateRequest>()
                val verified = QuizKeyTransitionValidator.verify(parentId, body.transition) { keyId ->
                    services.quizKeyTransitions.knownPublicKey(parentId, keyId)
                }
                val row = services.quizKeyTransitions.create(parentId, verified)
                call.respond(HttpStatusCode.Created, quizKeyTransitionView(row))
            }
            post("/{id}/revoke") {
                val parentId = call.requireParent(services)
                call.limit(services.quizKeyLimiter, "quiz-key-revoke:$parentId")
                val id = call.parameters["id"] ?: notFound()
                if (!id.matches(Regex("^quiz-key-transition-[a-f0-9-]{36}$"))) {
                    badRequest("Key transition id has an invalid format")
                }
                val row = services.quizKeyTransitions.revoke(parentId, id)
                    ?: notFound("Key transition not found")
                call.respond(quizKeyTransitionView(row))
            }
        }

        post("/api/v1/auth/register") {
            call.limit(services.authLimiter, "register")
            val body = call.receive<ParentRegisterRequest>()
            val email = ParentRepository.normalizeEmail(body.email)
            val hashed = services.passwordHasher.hash(body.password)
            val parent = services.parents.create(email, hashed)
            call.respond(HttpStatusCode.Created, services.authResponse(parent))
        }
        post("/api/v1/auth/login") {
            call.limit(services.authLimiter, "login")
            val body = call.receive<ParentLoginRequest>()
            val email = ParentRepository.normalizeEmail(body.email)
            val parent = services.parents.findByEmail(email)
            val matches = services.passwordHasher.verify(
                body.password, parent?.passwordHash ?: services.dummyHash
            )
            if (parent == null || !matches) unauthorized("Invalid email or password")
            call.respond(services.authResponse(parent))
        }
        get("/api/v1/me") {
            val parentId = call.requireParent(services)
            val parent = services.parents.findById(parentId) ?: unauthorized("Account no longer exists")
            call.respond(ParentView(parent.id, parent.email))
        }
        get("/api/v1/me/sessions") {
            val identity = call.requireParentSession(services)
            val now = Instant.now()
            call.respond(services.parentSessions.list(identity.parentId).map { row ->
                parentSessionView(row, identity.sessionId, now)
            })
        }
        post("/api/v1/me/sessions/revoke-others") {
            val identity = call.requireParentSession(services)
            call.limit(services.parentSessionLimiter, "session-revoke:$identity.parentId")
            val revoked = services.parentSessions.revokeOthers(identity.parentId, identity.sessionId)
            call.respond(ParentSessionRevokeOthersResponse(revoked))
        }
        post("/api/v1/me/sessions/{id}/revoke") {
            val identity = call.requireParentSession(services)
            call.limit(services.parentSessionLimiter, "session-revoke:$identity.parentId")
            val sessionId = call.parameters["id"] ?: notFound()
            if (!sessionId.matches(Regex("^[a-f0-9-]{36}$"))) notFound("Session not found")
            val row = services.parentSessions.revoke(identity.parentId, sessionId)
                ?: notFound("Session not found")
            call.respond(parentSessionView(row, identity.sessionId, Instant.now()))
        }
        post("/api/v1/me/delete") {
            val parentId = call.requireParent(services)
            call.limit(services.accountDeletionLimiter, "account-delete:$parentId")
            val body = call.receive<AccountDeleteRequest>()
            if (body.confirmation != "DELETE" || body.email.length > 254
                || body.password.length > 256
            ) {
                forbidden("Account deletion confirmation failed")
            }
            val deleted = services.accountDeletion.delete(
                parentId,
                body.email,
                body.password,
                body.confirmation
            )
            if (!deleted) forbidden("Account deletion confirmation failed")
            call.respond(HttpStatusCode.NoContent)
        }
        post("/api/v1/device/register") {
            call.limit(services.deviceLimiter, "device-register")
            val body = call.receive<DeviceRegisterRequest>()
            validateDevicePublicKey(body.public_key)
            val code = Codes.pairingCode()
            val expires = Codes.now().plusSeconds(services.config.pairingTtlSeconds)
            val device = services.devices.register(body, Codes.pairingHash(code), expires)
            val token = services.tokens.deviceToken(device.id, services.config.deviceTokenTtlSeconds)
            call.respond(HttpStatusCode.Created,
                DeviceRegisterResponse(token, code, expires.toEpochMilli()))
        }
        post("/api/v1/pairing/claim") {
            val parentId = call.requireParent(services)
            call.limit(services.pairingLimiter, "claim:$parentId")
            val body = call.receive<PairingClaimRequest>()
            val code = body.code.trim().uppercase()
            if (code.length != 8) notFound("Pairing code is invalid or expired")
            val device = services.devices.claim(Codes.pairingHash(code), parentId)
                ?: notFound("Pairing code is invalid or expired")
            services.audit.append(device.id, "parent:$parentId", "pairing.claimed", device.deviceId)
            call.respond(deviceView(device, 0))
        }

        route("/api/v1/devices") {
            get {
                val parentId = call.requireParent(services)
                call.respond(services.devices.listByParent(parentId)
                    .map { deviceView(it.device, it.latestRevision) })
            }
            get("/{id}") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                val latest = services.policies.latest(device.id)?.revision ?: 0
                call.respond(deviceView(device, latest))
            }
            delete("/{id}") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                if (!services.devices.revoke(device.id, parentId)) notFound("Device not found")
                services.audit.append(device.id, "parent:$parentId", "pairing.revoked", device.deviceId)
                call.respond(HttpStatusCode.NoContent)
            }
            put("/{id}/policy") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                if (device.revokedAt != null) conflict("Device has been revoked")
                if (!device.confirmed) conflict("Device has not confirmed pairing yet")
                val body = call.receive<PolicyUpdateRequest>()
                val row = services.policies.create(device.id) { revision ->
                    val built = PolicyValidator.validateAndBuild(body.policy, revision)
                    val payload = canonicalJson(built)
                    PreparedPolicy(payload, services.config.policySigner.sign(payload),
                        services.config.policySigner.keyId)
                }
                services.audit.append(device.id, "parent:$parentId", "policy.published",
                    "revision=${row.revision}")
                call.respond(PolicyEnvelope(1, row.keyId, row.revision, row.payload, row.signature))
            }
            get("/{id}/policy") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                val row = services.policies.latest(device.id) ?: notFound("No policy published")
                call.respond(PolicyEnvelope(1, row.keyId, row.revision, row.payload, row.signature))
            }
            get("/{id}/policy/history") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                call.respond(services.policies.history(device.id).map { row ->
                    PolicyHistoryView(row.revision, row.keyId, row.createdAt.toString())
                })
            }
            post("/{id}/policy/{revision}/rollback") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                if (device.revokedAt != null) conflict("Device has been revoked")
                if (!device.confirmed) conflict("Device has not confirmed pairing yet")
                val sourceRevision = call.parameters["revision"]?.toLongOrNull()
                    ?: badRequest("Policy revision must be a positive integer")
                if (sourceRevision < 1) badRequest("Policy revision must be a positive integer")
                val source = services.policies.findRevision(device.id, sourceRevision)
                    ?: notFound("Policy revision not found")
                val sourcePolicy = services.json.parseToJsonElement(source.payload).jsonObject
                val row = services.policies.create(device.id) { revision ->
                    val built = PolicyValidator.validateAndBuild(sourcePolicy, revision)
                    val payload = canonicalJson(built)
                    PreparedPolicy(payload, services.config.policySigner.sign(payload),
                        services.config.policySigner.keyId)
                }
                services.audit.append(device.id, "parent:$parentId", "policy.rolled_back",
                    "source=${source.revision}:revision=${row.revision}")
                call.respond(PolicyEnvelope(1, row.keyId, row.revision, row.payload, row.signature))
            }
            get("/{id}/usage") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                val today = LocalDate.now(ZoneOffset.UTC)
                val from = parseDate(call.request.queryParameters["from"], today.minusDays(6))
                val to = parseDate(call.request.queryParameters["to"], today)
                if (from.isAfter(to)) badRequest("from must not be after to")
                if (from.isBefore(to.minusDays(90))) badRequest("Usage history is limited to 90 days")
                call.respond(services.usage.list(device.id, from, to).map { row ->
                    val stored = services.json.decodeFromString<StoredUsage>(row.payload)
                    UsageReportView(row.day, stored.appMinutes, stored.health, row.createdAt.toString())
                })
            }
            get("/{id}/audit") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                call.respond(services.audit.list(device.id).map {
                    AuditView(it.id, it.actor, it.event, it.detail, it.createdAt.toString())
                })
            }
            get("/{id}/apps") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                call.respond(services.apps.list(device.id).map(::appView))
            }
            get("/{id}/grants") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                call.respond(services.grants.list(device.id).map(::grantView))
            }
            post("/{id}/grants") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                if (device.revokedAt != null) conflict("Device has been revoked")
                if (!device.confirmed) conflict("Device has not confirmed pairing yet")
                val body = call.receive<GrantCreateRequest>()
                PolicyValidator.requirePackage(body.packageName)
                if (body.minutes !in 1..1440) badRequest("minutes must be between 1 and 1440")
                if (body.expiresInMinutes !in 1..10_080) {
                    badRequest("expires_in_minutes must be between 1 and 10080")
                }
                requireTemporaryGrantTarget(services, device, body.packageName)
                val created = services.grants.create(
                    device.id, body.packageName, body.minutes, body.expiresInMinutes
                )
                services.audit.append(device.id, "parent:$parentId", "grant.created",
                    "${body.packageName}:${body.minutes}m:${created.id}")
                call.respond(HttpStatusCode.Created, grantView(created))
            }
            post("/{id}/grants/{grantId}/revoke") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                val grantId = call.parameters["grantId"] ?: notFound()
                val result = services.grants.revoke(device.id, grantId, "parent:$parentId")
                    ?: notFound("Temporary grant not found")
                if (result.changed) {
                    services.audit.append(device.id, "parent:$parentId", "grant.revoked",
                        "${result.grant.packageName}:${result.grant.minutes}m:${result.grant.id}")
                }
                call.respond(grantView(result.grant))
            }
            get("/{id}/screenshots") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                call.respond(services.screenshots.list(device.id).map(::screenshotView))
            }
            post("/{id}/call-requests") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                if (device.revokedAt != null) conflict("Device has been revoked")
                if (!device.confirmed) conflict("Device has not confirmed pairing yet")
                val body = call.receive<CallCreateRequest>()
                if (body.kind !in setOf("audio", "video", "screen", "screenshot")) {
                    badRequest("Unsupported call kind")
                }
                val created = services.calls.create(device.id, body.kind, services.config.callTtlSeconds)
                services.audit.append(device.id, "parent:$parentId", "call.requested", body.kind)
                call.respond(HttpStatusCode.Created, callView(created))
            }
            get("/{id}/call-requests") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                call.respond(services.calls.listForDevice(device.id).map(::callView))
            }
            get("/{id}/quiz-results") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                if (limit !in 1..100) badRequest("limit must be between 1 and 100")
                call.respond(services.quizResults.list(device.id, limit)
                    .map { quizResultView(it, services) })
            }
            post("/{id}/notices") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                if (device.revokedAt != null) conflict("Device has been revoked")
                if (!device.confirmed) conflict("Device has not confirmed pairing yet")
                call.limit(services.pairingLimiter, "notice-create:$parentId")
                val body = call.receive<NoticeCreateRequest>()
                if (body.kind !in setOf("callback", "task")) badRequest("Unsupported notice kind")
                val message = body.message.filterNot { it.isISOControl() }.trim()
                if (message.length > 200) badRequest("Notice message must be at most 200 characters")
                val created = services.notices.create(device.id, body.kind, message)
                services.audit.append(device.id, "parent:$parentId", "notice.created",
                    "${body.kind}:${created.id}")
                call.respond(HttpStatusCode.Created, noticeView(created))
            }
            get("/{id}/notices") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                call.respond(services.notices.list(device.id).map(::noticeView))
            }
            post("/{id}/quiz-triggers") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                if (device.revokedAt != null) conflict("Device has been revoked")
                if (!device.confirmed) conflict("Device has not confirmed pairing yet")
                call.limit(services.pairingLimiter, "quiz-trigger-create:$parentId")
                val body = call.receive<ManualQuizTriggerCreateRequest>()
                PolicyValidator.requirePackage(body.packageName)
                if (body.expiresInMinutes !in 1..60) {
                    badRequest("expires_in_minutes must be between 1 and 60")
                }
                requireManualQuizTarget(services, device, body.packageName)
                val created = services.manualQuizTriggers.create(
                    device.id, parentId, body.packageName, body.expiresInMinutes
                )
                services.audit.append(device.id, "parent:$parentId", "quiz.trigger.manual",
                    "${created.id}:${body.packageName}")
                call.respond(HttpStatusCode.Created, manualQuizTriggerView(created))
            }
            get("/{id}/quiz-triggers") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                call.respond(services.manualQuizTriggers.list(device.id, parentId)
                    .map(::manualQuizTriggerView))
            }
            get("/{id}/help-requests") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                call.respond(services.helpRequests.listForParent(parentId, device.id)
                    .map(::helpRequestView))
            }
            post("/{id}/help-requests/{requestId}/respond") {
                val parentId = call.requireParent(services)
                val device = call.ownedDevice(services, parentId)
                if (device.revokedAt != null) conflict("Device has been revoked")
                val requestId = call.parameters["requestId"] ?: notFound()
                if (!requestId.matches(Regex("^help-[a-f0-9-]{36}$"))) {
                    badRequest("Help request id has an invalid format")
                }
                val body = call.receive<HelpRequestRespondRequest>()
                val reply = body.reply.filterNot { it.isISOControl() }.trim()
                if (reply.length !in 1..1_000) {
                    badRequest("A help reply must be 1 to 1000 characters")
                }
                val row = services.helpRequests.respond(device.id, parentId, requestId, reply)
                    ?: notFound("Help request not found or expired")
                services.audit.append(device.id, "parent:$parentId", "help.responded", row.id)
                call.respond(helpRequestView(row))
            }
        }
        get("/api/v1/call-requests/{id}/token") {
            val parentId = call.requireParent(services)
            val callId = call.parameters["id"] ?: notFound()
            val request = services.calls.find(callId) ?: notFound("Call request not found")
            val device = services.devices.findById(request.deviceId) ?: notFound("Device not found")
            if (device.parentId != parentId) notFound("Call request not found")
            if (request.status != "accepted") conflict("The child has not accepted this request")
            if (request.expiresAt.isBefore(Codes.now())) conflict("Call request has expired")
            if (request.kind == "screenshot") conflict("Screenshot requests do not use LiveKit")
            val livekit = services.livekit ?: throw ApiException(HttpStatusCode.ServiceUnavailable,
                "LiveKit is not configured")
            val token = livekit.issue("parent-$parentId", roomName(device.id, request.id))
            call.respond(LiveKitResponse(token.url, token.token, token.room, token.expiresAt))
        }
        post("/api/v1/call-requests/{id}/end") {
            val parentId = call.requireParent(services)
            val callId = call.parameters["id"] ?: notFound()
            val request = services.calls.find(callId) ?: notFound("Call request not found")
            val device = services.devices.findById(request.deviceId) ?: notFound("Call request not found")
            if (device.parentId != parentId) notFound("Call request not found")
            call.respond(callView(endCallSession(services, device, request, "parent:$parentId")))
        }

        route("/api/v1/device") {
            post("/token") {
                call.limit(services.deviceTokenLimiter, "device-token")
                val body = call.receive<DeviceTokenRequest>()
                if (!body.nonce.matches(Regex("^[A-Za-z0-9_-]{16,128}$"))) {
                    badRequest("nonce has an invalid format")
                }
                val now = System.currentTimeMillis()
                if (kotlin.math.abs(now - body.timestamp) > 5 * 60 * 1000L) {
                    unauthorized("Device proof has expired")
                }
                val device = services.devices.findByDeviceId(body.device_id)
                    ?: unauthorized("Device is not registered")
                if (device.revokedAt != null) unauthorized("Device has been revoked")
                if (!services.deviceProofReplayGuard.accept("${device.id}:${body.nonce}")) {
                    unauthorized("Device proof was already used")
                }
                val message = "guard-device-token-v1\n${device.deviceId}\n${body.timestamp}\n${body.nonce}"
                if (!verifyDeviceSignature(device.publicKey, message, body.signature)) {
                    unauthorized("Device proof signature is invalid")
                }
                val ttl = services.config.deviceTokenTtlSeconds
                call.respond(DeviceTokenResponse(
                    services.tokens.deviceToken(device.id, ttl),
                    System.currentTimeMillis() + ttl * 1000
                ))
            }
            get("/me") {
                val device = call.requireDevice(services)
                val parentEmail = device.parentId?.let { services.devices.parentEmail(device.id) }
                call.respond(DeviceMeView(device.id, device.name, device.parentId != null,
                    device.confirmed, parentEmail, services.policies.latest(device.id)?.revision ?: 0,
                    services.config.policySigner.keyId, services.config.policySigner.publicKeyBase64))
            }
            post("/confirm-pairing") {
                val device = call.requireDevice(services)
                val parentId = device.parentId ?: conflict("Device is not paired yet")
                if (!services.devices.confirm(device.id, parentId)) notFound("Device not found")
                services.audit.append(device.id, "device:${device.id}", "pairing.confirmed", device.deviceId)
                call.respond(PairingConfirmResponse(true, true))
            }
            get("/policy") {
                val device = call.requireDevice(services)
                val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
                if (after < 0) badRequest("after must not be negative")
                val row = services.policies.latestAfter(device.id, after)
                if (row == null) call.respond(HttpStatusCode.NoContent)
                else call.respond(PolicyEnvelope(1, row.keyId, row.revision, row.payload, row.signature))
            }
            get("/quiz-pack") {
                val device = call.requireDevice(services)
                val parentId = device.parentId ?: conflict("Device is not paired yet")
                val requestedBase = call.request.queryParameters["base_version"]?.let { value ->
                    value.toLongOrNull()?.takeIf { it > 0L }
                        ?: badRequest("base_version must be a positive integer")
                }
                val row = services.quizPacks.findActive(parentId)
                if (row == null) call.respond(HttpStatusCode.NoContent)
                else {
                    val deltaOnly = requestedBase != null && row.deltaBaseVersion == requestedBase
                            && !row.deltaEnvelope.isNullOrBlank()
                    call.respond(DeviceQuizPackView(
                        row.version, row.keyId, row.publicKey,
                        if (deltaOnly) "" else row.envelope, row.publishedAt,
                        row.deltaEnvelope, row.deltaBaseVersion
                    ))
                }
            }
            get("/quiz-key-transitions") {
                val device = call.requireDevice(services)
                val parentId = device.parentId ?: conflict("Device is not yet paired")
                call.respond(services.quizKeyTransitions.list(parentId)
                    .map(::quizKeyTransitionView))
            }
            post("/usage") {
                val device = call.requireDevice(services)
                val body = call.receive<UsageReportRequest>()
                PolicyValidator.validateUsage(body.day, body.app_minutes, body.health)
                val payload = services.json.encodeToString(StoredUsage(body.app_minutes, body.health))
                services.usage.save(device.id, LocalDate.parse(body.day), payload)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/quiz-results") {
                val device = call.requireDevice(services)
                call.limit(services.deviceLimiter, "quiz-result:${device.id}")
                val body = call.receive<QuizAttemptResultRequest>()
                val checked = validateQuizAttempt(body)
                val details = services.json.encodeToString(checked.questions)
                val saved = services.quizResults.save(
                    device.id, checked.attemptId, Instant.ofEpochMilli(checked.occurredAt),
                    checked.targetPackage, checked.triggerId, checked.policyRevision,
                    checked.quizVersion, checked.questions.size,
                    checked.questions.count { it.correct }, checked.rewardGranted, details
                )
                if (saved.inserted) {
                    services.audit.append(device.id, "device:${device.id}", "quiz.result",
                        "${checked.targetPackage}:${saved.row.correctCount}/${saved.row.questionCount}")
                    val triggerId = checked.triggerId
                    if (triggerId != null && triggerId.startsWith("quiz-manual-")) {
                        services.manualQuizTriggers.complete(device.id, triggerId)
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }
            get("/notices") {
                val device = call.requireDevice(services)
                call.respond(services.notices.pendingFor(device.id).map(::noticeView))
            }
            get("/quiz-triggers") {
                val device = call.requireDevice(services)
                call.respond(services.manualQuizTriggers.pendingFor(device.id)
                    .map(::manualQuizTriggerView))
            }
            post("/help-requests") {
                val device = call.requireDevice(services)
                val parentId = device.parentId ?: conflict("Device is not paired yet")
                if (!device.confirmed) conflict("Device has not confirmed pairing yet")
                call.limit(services.helpRequestLimiter, "help-request:${device.id}")
                val body = call.receive<HelpRequestCreateRequest>()
                if (!body.clientRequestId.matches(Regex("^[A-Za-z0-9._:-]{8,128}$"))) {
                    badRequest("client_request_id has an invalid format")
                }
                val reason = body.reason.trim().lowercase()
                if (!reason.matches(Regex("^[a-z][a-z0-9_]{0,31}$"))) {
                    badRequest("reason has an invalid format")
                }
                val targetPackage = body.targetPackage.trim()
                if (targetPackage.isNotEmpty()) PolicyValidator.requirePackage(targetPackage)
                val triggerId = body.triggerId.trim()
                if (triggerId.isNotEmpty()
                        && !triggerId.matches(Regex("(quiz-trigger|quiz-manual|quiz-budget)-[a-f0-9]{24}"))) {
                    badRequest("trigger_id has an invalid format")
                }
                val message = body.message.filterNot { it.isISOControl() }.trim()
                if (message.length > 500) badRequest("Help message must be at most 500 characters")
                val saved = services.helpRequests.create(device.id, parentId,
                    body.clientRequestId, targetPackage, triggerId, reason, message)
                if (saved.created) {
                    services.audit.append(device.id, "device:${device.id}", "help.requested",
                        "${saved.row.id}:${reason}" + if (targetPackage.isEmpty()) "" else ":$targetPackage")
                }
                call.respond(if (saved.created) HttpStatusCode.Created else HttpStatusCode.OK,
                    helpRequestView(saved.row))
            }
            get("/help-requests") {
                val device = call.requireDevice(services)
                call.respond(services.helpRequests.listForDevice(device.id).map(::helpRequestView))
            }
            post("/help-requests/{id}/ack") {
                val device = call.requireDevice(services)
                val requestId = call.parameters["id"] ?: notFound()
                if (!requestId.matches(Regex("^help-[a-f0-9-]{36}$"))) {
                    badRequest("Help request id has an invalid format")
                }
                val row = services.helpRequests.acknowledge(device.id, requestId)
                    ?: notFound("Help request not found")
                services.audit.append(device.id, "device:${device.id}", "help.acknowledged", row.id)
                call.respond(helpRequestView(row))
            }
            post("/quiz-triggers/{id}/ack") {
                val device = call.requireDevice(services)
                val triggerId = call.parameters["id"] ?: notFound()
                if (!triggerId.matches(Regex("quiz-manual-[a-f0-9]{24}"))) {
                    badRequest("Quiz trigger id has an invalid format")
                }
                val body = call.receive<ManualQuizTriggerAckRequest>()
                if (body.status !in setOf("accepted", "rejected", "completed")) {
                    badRequest("Unsupported quiz trigger status")
                }
                val detail = body.detail.filterNot { it.isISOControl() }.trim()
                if (detail.length > 200) badRequest("detail must be at most 200 characters")
                val row = services.manualQuizTriggers.acknowledge(
                    device.id, triggerId, body.status, detail
                ) ?: conflict("Quiz trigger is no longer pending")
                services.audit.append(device.id, "device:${device.id}",
                    "quiz.trigger.${row.status}",
                    if (detail.isEmpty()) row.id else "${row.id}:$detail")
                call.respond(manualQuizTriggerView(row))
            }
            post("/notices/{id}/ack") {
                val device = call.requireDevice(services)
                val noticeId = call.parameters["id"] ?: notFound()
                val row = services.notices.acknowledge(device.id, noticeId)
                    ?: conflict("Notice is no longer pending")
                services.audit.append(device.id, "device:${device.id}", "notice.acknowledged", row.id)
                call.respond(noticeView(row))
            }
            post("/policy-ack") {
                val device = call.requireDevice(services)
                val body = call.receive<PolicyAckRequest>()
                if (body.revision < 0) badRequest("revision must not be negative")
                if (body.errors.size > 20) badRequest("Too many errors")
                services.audit.append(device.id, "device:${device.id}", "policy.applied",
                    "revision=${body.revision} verified=${body.verified} errors=${body.errors.joinToString("|")}")
                call.respond(HttpStatusCode.NoContent)
            }
            post("/audit") {
                val device = call.requireDevice(services)
                val body = call.receive<DeviceAuditRequest>()
                if (!body.event.matches(Regex("^[A-Za-z0-9._:-]{1,80}$"))) {
                    badRequest("event has an invalid format")
                }
                if (body.detail.length > 1000) badRequest("detail is too long")
                services.audit.append(device.id, "device:${device.id}", body.event, body.detail)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/apps") {
                val device = call.requireDevice(services)
                val body = call.receive<DeviceAppsRequest>()
                if (body.apps.size > 2000) badRequest("At most 2000 apps are supported")
                val normalized = body.apps.map { app ->
                    PolicyValidator.requirePackage(app.packageName)
                    if (app.category !in -1..32) badRequest("App category is out of range")
                    val label = app.label.filterNot { it.isISOControl() }.trim().take(120)
                    if (label.isEmpty()) badRequest("App label must not be empty")
                    app.copy(label = label)
                }
                if (normalized.map { it.packageName }.toSet().size != normalized.size) {
                    badRequest("apps contains duplicate packages")
                }
                services.apps.replace(device.id, normalized)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/grants") {
                val device = call.requireDevice(services)
                call.respond(services.grants.pendingFor(device.id).map(::grantView))
            }
            get("/grants/revocations") {
                val device = call.requireDevice(services)
                val since = Instant.now().minusSeconds(30L * 24 * 60 * 60)
                call.respond(services.grants.revokedSince(device.id, since).map(::grantView))
            }
            post("/grants/{id}/apply") {
                val device = call.requireDevice(services)
                val grantId = call.parameters["id"] ?: notFound()
                val grant = services.grants.markApplied(device.id, grantId, "device:${device.id}")
                    ?: notFound("Temporary grant not found")
                if (grant.revokedAt != null) conflict("Temporary grant has been revoked")
                if (!grant.expiresAt.isAfter(Instant.now())) conflict("Temporary grant has expired")
                services.audit.append(device.id, "device:${device.id}", "grant.applied",
                    "${grant.packageName}:${grant.minutes}m:${grant.id}")
                call.respond(grantView(grant))
            }
            post("/screenshots") {
                val device = call.requireDevice(services)
                val body = call.receive<ScreenshotUploadRequest>()
                if (!body.callId.matches(Regex("^[A-Za-z0-9._:-]{1,128}$"))) {
                    badRequest("call_id has an invalid format")
                }
                if (body.width !in 1..4096 || body.height !in 1..4096) {
                    badRequest("Screenshot dimensions are out of range")
                }
                if (body.imageBase64.length !in 4..3_000_000) {
                    badRequest("Screenshot image is too large")
                }
                val image = try {
                    Base64.getDecoder().decode(body.imageBase64)
                } catch (exception: IllegalArgumentException) {
                    badRequest("Screenshot image is not valid base64")
                }
                if (image.size !in 3..2_000_000 || image[0] != 0xFF.toByte()
                        || image[1] != 0xD8.toByte() || image[2] != 0xFF.toByte()) {
                    badRequest("Screenshot must be a JPEG image up to 2 MiB")
                }
                val request = services.calls.find(body.callId)
                    ?: notFound("Screenshot request not found")
                if (request.deviceId != device.id || request.kind != "screenshot") {
                    notFound("Screenshot request not found")
                }
                if (request.status != "accepted") conflict("Screenshot request has not been accepted")
                if (request.expiresAt.isBefore(Codes.now())) conflict("Screenshot request has expired")
                val saved = services.screenshots.save(
                    device.id, request.id, image, body.width, body.height
                )
                services.audit.append(device.id, "device:${device.id}", "screenshot.received",
                    "${saved.id}:${saved.width}x${saved.height}")
                call.respond(HttpStatusCode.Created, screenshotView(saved))
            }
            get("/call-requests") {
                val device = call.requireDevice(services)
                call.respond(services.calls.pendingFor(device.id).map(::callView))
            }
            get("/call-requests/{id}") {
                val device = call.requireDevice(services)
                val callId = call.parameters["id"] ?: notFound()
                val request = services.calls.find(callId) ?: notFound("Call request not found")
                if (request.deviceId != device.id) notFound("Call request not found")
                call.respond(callView(request))
            }
            post("/call-requests/{id}/end") {
                val device = call.requireDevice(services)
                val callId = call.parameters["id"] ?: notFound()
                val request = services.calls.find(callId) ?: notFound("Call request not found")
                if (request.deviceId != device.id) notFound("Call request not found")
                call.respond(callView(endCallSession(
                    services, device, request, "device:${device.id}"
                )))
            }
            post("/call-requests/{id}/respond") {
                val device = call.requireDevice(services)
                val callId = call.parameters["id"] ?: notFound()
                val body = call.receive<CallRespondRequest>()
                val request = services.calls.respond(device.id, callId, body.accept)
                    ?: conflict("Call request is no longer pending")
                services.audit.append(device.id, "device:${device.id}",
                    if (body.accept) "call.accepted" else "call.rejected", request.id)
                call.respond(callView(request))
            }
            get("/call-requests/{id}/token") {
                val device = call.requireDevice(services)
                val callId = call.parameters["id"] ?: notFound()
                val request = services.calls.find(callId) ?: notFound("Call request not found")
                if (request.deviceId != device.id) notFound("Call request not found")
                if (request.status != "accepted") conflict("Call request has not been accepted")
                if (request.expiresAt.isBefore(Codes.now())) conflict("Call request has expired")
                if (request.kind == "screenshot") conflict("Screenshot requests do not use LiveKit")
                val livekit = services.livekit ?: throw ApiException(HttpStatusCode.ServiceUnavailable,
                    "LiveKit is not configured")
                val token = livekit.issue("device-${device.id}", roomName(device.id, request.id))
                call.respond(LiveKitResponse(token.url, token.token, token.room, token.expiresAt))
            }
        }
        get("/api/v1/screenshots/{id}") {
            val parentId = call.requireParent(services)
            val screenshotId = call.parameters["id"] ?: notFound()
            val row = services.screenshots.find(screenshotId) ?: notFound("Screenshot not found")
            val device = services.devices.findById(row.deviceId) ?: notFound("Screenshot not found")
            if (device.parentId != parentId) notFound("Screenshot not found")
            call.response.headers.append(HttpHeaders.CacheControl, "private, no-store")
            call.respondBytes(row.image, ContentType.Image.JPEG)
        }
    }
}

fun ApplicationCall.bearerToken(): String {
    val header = request.headers[HttpHeaders.Authorization] ?: unauthorized()
    if (!header.startsWith("Bearer ")) unauthorized()
    val token = header.substring(7).trim()
    if (token.isEmpty()) unauthorized()
    return token
}

fun ApplicationCall.requireParent(services: Services): String {
    return requireParentSession(services).parentId
}

fun ApplicationCall.requireParentSession(services: Services): ParentIdentity {
    val identity = services.tokens.verifyParent(bearerToken())
        ?: unauthorized("Invalid or expired token")
    if (!services.parentSessions.isActive(identity.parentId, identity.sessionId)) {
        unauthorized("Parent session has been revoked or expired")
    }
    if (services.parents.findById(identity.parentId) == null) unauthorized("Invalid or expired token")
    return identity
}

fun ApplicationCall.requireDevice(services: Services): DeviceRow {
    val id = services.tokens.verifyDevice(bearerToken()) ?: unauthorized("Invalid or expired token")
    val device = services.devices.findById(id) ?: unauthorized("Device token is no longer valid")
    if (device.revokedAt != null) unauthorized("Device token has been revoked")
    services.devices.touch(device.id)
    return device
}

fun ApplicationCall.ownedDevice(services: Services, parentId: String): DeviceRow {
    val id = parameters["id"] ?: notFound()
    val device = services.devices.findById(id) ?: notFound("Device not found")
    if (device.parentId != parentId) notFound("Device not found")
    return device
}

fun ApplicationCall.limit(limiter: RateLimiter, key: String) {
    val address = request.origin.remoteHost
    if (!limiter.allow("$key:$address")) {
        throw ApiException(HttpStatusCode.TooManyRequests, "Too many requests; try again shortly")
    }
}

fun deviceView(device: DeviceRow, latestRevision: Long) = DeviceView(
    device.id, device.deviceId, device.name, device.platform,
    device.parentId != null, device.confirmed, device.revokedAt != null,
    device.createdAt.toString(), device.lastSeenAt?.toString(), latestRevision
)

fun callView(row: CallRow) = CallView(
    row.id, row.deviceId, row.kind, row.status,
    row.createdAt.toString(), row.expiresAt.toString(), row.endedAt?.toString()
)

fun parentSessionView(row: ParentSessionRow, currentSessionId: String, now: Instant) =
    ParentSessionView(
        row.id,
        row.createdAt.toString(),
        row.expiresAt.toString(),
        row.lastSeenAt.toString(),
        row.revokedAt?.toString(),
        row.id == currentSessionId,
        when {
            row.revokedAt != null -> "revoked"
            !row.expiresAt.isAfter(now) -> "expired"
            else -> "active"
        }
    )

fun appView(row: DeviceAppRow) = DeviceAppView(
    row.packageName, row.label, row.category, row.enabled, row.updatedAt.toString()
)

fun grantView(row: GrantRow) = GrantView(
    row.id, row.packageName, row.minutes, row.createdAt.toString(),
    row.expiresAt.toString(), row.appliedAt != null, row.appliedAt?.toString(),
    row.revokedAt != null, row.revokedAt?.toString(), row.revokedBy
)

fun screenshotView(row: ScreenshotRow) = ScreenshotView(
    row.id, row.deviceId, row.callId, row.width, row.height,
    row.createdAt.toString(), row.expiresAt.toString()
)

fun quizResultView(row: QuizResultRow, services: Services) = QuizAttemptResultView(
    row.id, row.deviceId, row.attemptId, row.occurredAt.toString(), row.targetPackage,
    row.triggerId, row.policyRevision, row.quizVersion, row.questionCount, row.correctCount,
    row.rewardGranted, services.json.decodeFromString(row.questions), row.receivedAt.toString()
)

fun noticeView(row: NoticeRow) = NoticeView(
    row.id, row.deviceId, row.kind, row.message, row.status,
    row.createdAt.toString(), row.expiresAt.toString(), row.acknowledgedAt?.toString()
)

fun manualQuizTriggerView(row: ManualQuizTriggerRow) = ManualQuizTriggerView(
    row.id, row.deviceId, row.targetPackage, row.status, row.createdAt.toString(),
    row.expiresAt.toString(), row.deliveredAt?.toString(), row.finishedAt?.toString(),
    row.detail
)

/**
 * A parent may only raise a manual quiz for an app that the active signed
 * policy already manages and for which the gate is enabled. The device repeats
 * these checks against its locally verified copy before showing a prompt.
 */
fun requireManualQuizTarget(services: Services, device: DeviceRow, packageName: String) {
    val latest = services.policies.latest(device.id)
        ?: conflict("Publish a signed policy before raising a quiz")
    val policy = try {
        services.json.parseToJsonElement(latest.payload) as? JsonObject
    } catch (exception: Exception) {
        null
    } ?: conflict("The active policy cannot be read")
    fun enabled(name: String) = (policy[name] as? JsonPrimitive)?.booleanOrNull == true
    fun contains(name: String) = (policy[name] as? JsonArray)?.any {
        (it as? JsonPrimitive)?.contentOrNull == packageName
    } == true
    if (!enabled("enabled") || !enabled("quiz_gate_enabled")) {
        conflict("The quiz gate is disabled in the active policy")
    }
    val budgets = policy["daily_budgets"] as? JsonObject
    val schedules = policy["schedules"] as? JsonObject
    if (budgets?.containsKey(packageName) != true && schedules?.containsKey(packageName) != true) {
        conflict("The package is not managed by the active policy")
    }
    if (contains("blocked_packages")) conflict("The package is always blocked")
    if (contains("always_allowed_packages")) conflict("The package is always allowed")
}

/** A parent allowance is only meaningful for a package the active policy restricts. */
fun requireTemporaryGrantTarget(services: Services, device: DeviceRow, packageName: String) {
    val latest = services.policies.latest(device.id)
        ?: conflict("Publish a signed policy before creating an allowance")
    val policy = try {
        services.json.parseToJsonElement(latest.payload) as? JsonObject
    } catch (exception: Exception) {
        null
    } ?: conflict("The active policy cannot be read")
    fun enabled(name: String) = (policy[name] as? JsonPrimitive)?.booleanOrNull == true
    fun contains(name: String) = (policy[name] as? JsonArray)?.any {
        (it as? JsonPrimitive)?.contentOrNull == packageName
    } == true
    if (!enabled("enabled")) conflict("Guard is disabled in the active policy")
    val budgets = policy["daily_budgets"] as? JsonObject
    val schedules = policy["schedules"] as? JsonObject
    if (budgets?.containsKey(packageName) != true && schedules?.containsKey(packageName) != true) {
        conflict("The package is not managed by the active policy")
    }
    if (contains("blocked_packages")) conflict("The package is always blocked")
    if (contains("always_allowed_packages")) conflict("The package is always allowed")
}

fun validateQuizAttempt(body: QuizAttemptResultRequest): QuizAttemptResultRequest {
    if (!body.attemptId.matches(Regex("^[A-Za-z0-9._:-]{8,128}$"))) {
        badRequest("attempt_id has an invalid format")
    }
    val now = System.currentTimeMillis()
    if (body.occurredAt < now - 30L * 24 * 60 * 60 * 1000
            || body.occurredAt > now + 5L * 60 * 1000) {
        badRequest("occurred_at is outside the accepted window")
    }
    PolicyValidator.requirePackage(body.targetPackage)
    if (body.triggerId != null
            && !body.triggerId.matches(Regex("(quiz-trigger|quiz-manual)-[a-f0-9]{24}"))) {
        badRequest("trigger_id has an invalid format")
    }
    if (body.policyRevision < 0 || body.quizVersion < 0) {
        badRequest("Policy and quiz versions must not be negative")
    }
    if (body.questions.size !in 1..10) badRequest("A quiz result needs 1 to 10 questions")
    val ids = HashSet<String>()
    val questions = body.questions.map { question ->
        if (!question.questionId.matches(Regex("^[A-Za-z0-9._:-]{1,128}"))) {
            badRequest("question_id has an invalid format")
        }
        if (!ids.add(question.questionId)) badRequest("Question ids must be unique")
        if (question.type !in setOf("", "single_choice", "multiple_choice", "true_false",
                "short_answer")) badRequest("Unsupported question type")
        if (question.grade !in 0..12) badRequest("Question grade is out of range")
        if (question.subject.length > 64 || question.difficulty.length > 16
                || question.language.length > 32 || question.tags.size > 16) {
            badRequest("Question metadata is too large")
        }
        if (question.tags.any { it.length > 48 || it.isBlank() }) {
            badRequest("Question tags are invalid")
        }
        question.copy(
            subject = question.subject.filterNot { it.isISOControl() }.trim().lowercase(),
            difficulty = question.difficulty.lowercase().trim(),
            language = question.language.lowercase().trim(),
            tags = question.tags.map { it.trim().lowercase() }.distinct()
        )
    }
    if (body.rewardGranted && questions.any { !it.correct }) {
        badRequest("A rewarded attempt must have every recorded answer correct")
    }
    return body.copy(questions = questions)
}

fun roomName(deviceId: String, callId: String) = "guard-$deviceId-$callId"

suspend fun endCallSession(
    services: Services,
    device: DeviceRow,
    request: CallRow,
    actor: String
): CallRow {
    if (request.status !in setOf("accepted", "ended")) {
        conflict("Only an accepted call can be ended")
    }
    val ended = services.calls.end(request.id)
        ?: conflict("Only an accepted call can be ended")
    val alreadyRevoked = ended.roomRevokedAt != null
    if (request.kind != "screenshot" && !alreadyRevoked) {
        val rooms = services.livekitRooms ?: throw ApiException(
            HttpStatusCode.ServiceUnavailable, "LiveKit is not configured"
        )
        try {
            withContext(Dispatchers.IO) {
                rooms.deleteRoom(roomName(device.id, ended.id))
            }
        } catch (exception: Exception) {
            services.audit.append(device.id, actor, "call.end_failed", "${ended.kind}:${ended.id}")
            throw ApiException(HttpStatusCode.BadGateway,
                "The media room could not be revoked; retry ending the call")
        }
    }
    val revoked = services.calls.markRoomRevoked(ended.id)
        ?: throw ApiException(HttpStatusCode.InternalServerError,
            "Call end state could not be recorded")
    if (!alreadyRevoked) {
        services.audit.append(device.id, actor, "call.ended", "${ended.kind}:${ended.id}")
    }
    return revoked
}

fun parseDate(value: String?, fallback: LocalDate): LocalDate =
    if (value == null || value.isBlank()) fallback else try {
        LocalDate.parse(value)
    } catch (exception: Exception) {
        badRequest("Date parameters must use ISO-8601 dates")
    }

fun validateDevicePublicKey(base64: String) {
    try {
        val key = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(base64))
        )
        val ec = key as? ECPublicKey ?: badRequest("Device key must be an EC public key")
        if (ec.params.curve.field.fieldSize != 256) badRequest("Device key must use P-256")
    } catch (exception: ApiException) {
        throw exception
    } catch (exception: Exception) {
        badRequest("Device public key is not valid X.509 base64")
    }
}

package com.guard.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.net.URI

class Services(
    val config: ServerConfig,
    val database: Database,
    quizUpstreamFetcher: QuizUpstreamFetcher = HttpQuizUpstreamFetcher(),
    liveKitRoomService: LiveKitRoomService? = null
) {
    val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }
    val parents = ParentRepository(database)
    val parentSessions = ParentSessionRepository(database)
    val devices = DeviceRepository(database)
    val policies = PolicyRepository(database)
    val usage = UsageRepository(database)
    val audit = AuditRepository(database)
    val calls = CallRepository(database)
    val apps = DeviceAppRepository(database)
    val grants = GrantRepository(database)
    val quizPacks = QuizPackRepository(database)
    val quizKeyTransitions = QuizKeyTransitionRepository(database)
    val quizUpstreams = QuizUpstreamRepository(database)
    val quizUpstreamSync = QuizUpstreamSync(quizUpstreams, quizPacks, quizUpstreamFetcher)
    val quizUpstreamScheduler = QuizUpstreamScheduler(quizUpstreams, quizUpstreamSync)
    val quizResults = QuizResultRepository(database)
    val notices = NoticeRepository(database)
    val helpRequests = HelpRequestRepository(database)
    val manualQuizTriggers = ManualQuizTriggerRepository(database)
    val screenshots = ScreenshotRepository(database)
    val retention = RetentionScheduler(screenshots, calls, parentSessions)
    val tokens = TokenService(config.jwtSecret)
    val passwordHasher = PasswordHasher()
    val dummyHash = passwordHasher.hash("guard-dummy-password-value")
    val accountDeletion = AccountDeletionService(database, passwordHasher, dummyHash)
    val deviceProofReplayGuard = ReplayGuard()
    val livekit: LiveKitTokenService? = if (
        config.livekitUrl != null && config.livekitApiKey != null && config.livekitApiSecret != null
    ) LiveKitTokenService(config.livekitUrl, config.livekitApiKey, config.livekitApiSecret) else null
    val livekitRooms: LiveKitRoomService? = liveKitRoomService ?: if (
        config.livekitUrl != null && config.livekitApiKey != null && config.livekitApiSecret != null
    ) LiveKitServerRoomService(
        config.livekitApiUrl ?: config.livekitUrl,
        config.livekitApiKey,
        config.livekitApiSecret
    ) else null
    val authLimiter = RateLimiter(20, 60_000)
    val pairingLimiter = RateLimiter(30, 60_000)
    val deviceLimiter = RateLimiter(30, 60_000)
    val deviceTokenLimiter = RateLimiter(30, 60_000)
    val quizPackLimiter = RateLimiter(20, 60_000)
    val quizUpstreamLimiter = RateLimiter(6, 60_000)
    val quizKeyLimiter = RateLimiter(10, 60 * 60_000)
    val helpRequestLimiter = RateLimiter(10, 10 * 60_000)
    val accountDeletionLimiter = RateLimiter(3, 60_000)
    val parentSessionLimiter = RateLimiter(30, 60_000)

    fun authResponse(parent: ParentRow): AuthResponse {
        val session = parentSessions.create(parent.id, config.parentTokenTtlSeconds)
        val token = tokens.parentToken(parent.id, session.id, config.parentTokenTtlSeconds)
        return AuthResponse(
            token,
            session.expiresAt.toEpochMilli(),
            ParentView(parent.id, parent.email)
        )
    }
}

class RateLimiter(private val limit: Int, private val windowMillis: Long) {
    private val attempts = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val entries = attempts.getOrPut(key) { ArrayDeque() }
        while (entries.isNotEmpty() && entries.first() <= now - windowMillis) entries.removeFirst()
        if (entries.size >= limit) return false
        entries.addLast(now)
        if (attempts.size > 10_000) attempts.entries.removeIf { it.value.isEmpty() }
        return true
    }
}

fun main() {
    val config = ServerConfig.fromEnvironment()
    val database = Database.open(config.databaseUrl, config.databaseUser, config.databasePassword)
    database.initialize()
    if (config.devMode) {
        println("Development mode: policy key id ${config.policySigner.keyId}")
        println("Pin this policy public key in the device: ${config.policySigner.publicKeyBase64}")
    }
    embeddedServer(Netty, port = config.port, host = config.host) {
        guardModule(Services(config, database), startBackgroundJobs = true)
        monitor.subscribe(io.ktor.server.application.ApplicationStopped) { database.close() }
    }.start(wait = true)
}

fun Application.guardModule(services: Services, startBackgroundJobs: Boolean = false) {
    install(ContentNegotiation) { json(services.json) }
    install(CallLogging) { level = Level.INFO }
    install(DefaultHeaders)
    install(ForwardedHeaders)
    install(CORS) {
        allowHeader(io.ktor.http.HttpHeaders.Authorization)
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Put)
        allowMethod(io.ktor.http.HttpMethod.Delete)
        services.config.corsOrigins.forEach { origin ->
            val authority = try { URI(origin).authority } catch (exception: Exception) { null }
            if (authority != null) allowHost(authority, schemes = listOf("http", "https"))
        }
    }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message))
        }
        exception<BadRequestException> { call, cause ->
            call.application.log.warn("Rejected malformed request: ${cause.javaClass.simpleName}")
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled request failure", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }
    if (startBackgroundJobs) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        services.quizUpstreamScheduler.start(scope)
        services.retention.start(scope)
        monitor.subscribe(io.ktor.server.application.ApplicationStopped) { scope.cancel() }
    }
    configureRoutes(services)
}

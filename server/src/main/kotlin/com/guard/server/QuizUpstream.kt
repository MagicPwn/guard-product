package com.guard.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Duration
import java.time.Instant

private const val UPSTREAM_MAX_BODY_BYTES = 8_000_000
private const val UPSTREAM_MAX_ERROR_CHARS = 300

@Serializable
data class QuizUpstreamUpsertRequest(
    val title: String = "Trusted upstream quiz pack",
    val url: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("interval_minutes") val intervalMinutes: Int = 60,
    val enabled: Boolean = true
)

@Serializable
data class QuizUpstreamView(
    val title: String,
    val url: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("key_id") val keyId: String,
    val enabled: Boolean,
    @SerialName("interval_minutes") val intervalMinutes: Int,
    @SerialName("last_checked_at") val lastCheckedAt: String?,
    @SerialName("last_success_at") val lastSuccessAt: String?,
    @SerialName("last_error") val lastError: String,
    @SerialName("last_status") val lastStatus: String,
    @SerialName("last_version") val lastVersion: Long?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class QuizUpstreamSyncResult(
    val ok: Boolean,
    val status: String,
    val version: Long? = null,
    val message: String = "",
    @SerialName("checked_at") val checkedAt: String
)

@Serializable
data class QuizUpstreamEventView(
    val id: String,
    val event: String,
    val detail: String,
    @SerialName("created_at") val createdAt: String
)

data class QuizUpstreamRow(
    val parentId: String,
    val title: String,
    val url: String,
    val publicKey: String,
    val keyId: String,
    val enabled: Boolean,
    val intervalMinutes: Int,
    val etag: String?,
    val lastModified: String?,
    val lastCheckedAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastError: String,
    val lastStatus: String,
    val lastVersion: Long?,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun nextDueAt(): Instant = lastCheckedAt?.plusSeconds(intervalMinutes * 60L) ?: Instant.MIN
}

data class UpstreamHttpResponse(
    val statusCode: Int,
    val body: String?,
    val etag: String?,
    val lastModified: String?
)

fun interface QuizUpstreamFetcher {
    fun fetch(source: QuizUpstreamRow): UpstreamHttpResponse
}

class QuizUpstreamException(val code: String) : RuntimeException(code)

/**
 * Restricts parent-configured upstreams to public HTTPS endpoints.
 *
 * <p>This is deliberately conservative: redirects are never followed, URL
 * credentials and fragments are rejected, and every resolved address must be
 * globally routable. DNS can still change between validation and connect, so a
 * deployment that accepts arbitrary parent-supplied URLs should also enforce an
 * egress allowlist at the network layer.
 */
object QuizUpstreamUrlPolicy {
    private const val MAX_URL_CHARS = 2_048

    fun validateForStorage(raw: String): URI {
        val uri = parse(raw)
        val scheme = uri.scheme?.lowercase()
            ?: throw QuizUpstreamException("upstream-url-invalid")
        if (scheme != "https") {
            throw QuizUpstreamException("upstream-url-https-required")
        }
        if (uri.rawUserInfo != null || uri.rawFragment != null) {
            throw QuizUpstreamException("upstream-url-credentials-or-fragment-not-allowed")
        }
        if (uri.host.isNullOrBlank()) throw QuizUpstreamException("upstream-url-host-required")
        return uri
    }

    fun validateForFetch(raw: String): URI {
        val uri = validateForStorage(raw)
        val addresses = try {
            InetAddress.getAllByName(uri.host)
        } catch (exception: Exception) {
            throw QuizUpstreamException("upstream-url-host-unresolved")
        }
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            throw QuizUpstreamException("upstream-url-private-address-rejected")
        }
        return uri
    }

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false
        return when {
            address is Inet4Address -> isPublicIpv4(address.address)
            address is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }
    }

    private fun parse(raw: String): URI {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_URL_CHARS) {
            throw QuizUpstreamException("upstream-url-invalid")
        }
        return try {
            URI(value)
        } catch (exception: Exception) {
            throw QuizUpstreamException("upstream-url-invalid")
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return when {
            a == 0 || a == 10 || a == 127 -> false
            a == 100 && b in 64..127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 0 -> false
            a == 192 && b == 168 -> false
            a == 192 && b == 88 && c == 99 -> false
            a == 198 && (b == 18 || b == 19) -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            a >= 224 -> false
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if ((first and 0xfe) == 0xfc) return false
        if (first == 0xfe && (second and 0xc0) == 0x80) return false
        if (first == 0xff) return false
        val mapped = bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        if (mapped) return isPublicIpv4(bytes.copyOfRange(12, 16))
        if (first == 0x20 && second == 0x01) {
            val third = bytes[2].toInt() and 0xff
            val fourth = bytes[3].toInt() and 0xff
            if (third == 0 && fourth == 0) return false
            if (third == 0x0d && fourth == 0xb8) return false
        }
        return (first and 0xe0) == 0x20
    }
}

class HttpQuizUpstreamFetcher : QuizUpstreamFetcher {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun fetch(source: QuizUpstreamRow): UpstreamHttpResponse {
        val uri = QuizUpstreamUrlPolicy.validateForFetch(source.url)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("User-Agent", "Guard-Quiz-Upstream/1")
            .apply {
                source.etag?.takeIf { it.isNotBlank() }?.let { header("If-None-Match", it) }
                source.lastModified?.takeIf { it.isNotBlank() }
                    ?.let { header("If-Modified-Since", it) }
            }
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw QuizUpstreamException("upstream-request-interrupted")
        } catch (exception: Exception) {
            throw QuizUpstreamException("upstream-request-failed")
        }
        val status = response.statusCode()
        if (status == 304) {
            response.body().close()
            return UpstreamHttpResponse(304, null, header(response, "etag"),
                header(response, "last-modified"))
        }
        if (status != 200) {
            response.body().close()
            throw QuizUpstreamException("upstream-http-$status")
        }
        val declaredLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
        if (declaredLength > UPSTREAM_MAX_BODY_BYTES) {
            response.body().close()
            throw QuizUpstreamException("upstream-body-too-large")
        }
        val body = response.body().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > UPSTREAM_MAX_BODY_BYTES) {
                    throw QuizUpstreamException("upstream-body-too-large")
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8)
        }
        return UpstreamHttpResponse(200, body, header(response, "etag"),
            header(response, "last-modified"))
    }

    private fun header(response: HttpResponse<*>, name: String): String? =
        response.headers().firstValue(name).orElse(null)?.take(512)
}

class QuizUpstreamRepository(private val database: Database) {
    fun upsert(
        parentId: String,
        title: String,
        url: String,
        key: QuizPublicKeyInfo,
        intervalMinutes: Int,
        enabled: Boolean
    ): QuizUpstreamRow = database.transaction { connection ->
        val old = findByParent(connection, parentId)
        val now = Instant.now()
        if (old == null) {
            connection.prepareStatement(
                """INSERT INTO quiz_upstream_sources
                   (parent_id, title, url, public_key, key_id, enabled, interval_minutes,
                    last_error, last_status, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, '', 'never', ?, ?)"""
            ).use { statement ->
                statement.setString(1, parentId)
                statement.setString(2, title)
                statement.setString(3, url)
                statement.setString(4, key.normalizedBase64)
                statement.setString(5, key.keyId)
                statement.setBoolean(6, enabled)
                statement.setInt(7, intervalMinutes)
                statement.setTimestamp(8, Timestamp.from(now))
                statement.setTimestamp(9, Timestamp.from(now))
                statement.executeUpdate()
            }
        } else {
            val endpointChanged = old.url != url || old.keyId != key.keyId
            connection.prepareStatement(
                """UPDATE quiz_upstream_sources SET
                   title = ?, url = ?, public_key = ?, key_id = ?, enabled = ?,
                   interval_minutes = ?, updated_at = ?,
                   etag = CASE WHEN ? THEN NULL ELSE etag END,
                   last_modified = CASE WHEN ? THEN NULL ELSE last_modified END,
                   last_checked_at = CASE WHEN ? THEN NULL ELSE last_checked_at END,
                   last_success_at = CASE WHEN ? THEN NULL ELSE last_success_at END,
                   last_error = CASE WHEN ? THEN '' ELSE last_error END,
                   last_status = CASE WHEN ? THEN 'never' ELSE last_status END,
                   last_version = CASE WHEN ? THEN NULL ELSE last_version END
                   WHERE parent_id = ?"""
            ).use { statement ->
                statement.setString(1, title)
                statement.setString(2, url)
                statement.setString(3, key.normalizedBase64)
                statement.setString(4, key.keyId)
                statement.setBoolean(5, enabled)
                statement.setInt(6, intervalMinutes)
                statement.setTimestamp(7, Timestamp.from(now))
                repeat(7) { offset -> statement.setBoolean(8 + offset, endpointChanged) }
                statement.setString(15, parentId)
                statement.executeUpdate()
            }
        }
        findByParent(connection, parentId) ?: error("Quiz upstream was not persisted")
    }

    fun find(parentId: String): QuizUpstreamRow? = database.withConnection { connection ->
        findByParent(connection, parentId)
    }

    fun delete(parentId: String): Boolean = database.withConnection { connection ->
        connection.prepareStatement("DELETE FROM quiz_upstream_sources WHERE parent_id = ?")
            .use { statement ->
                statement.setString(1, parentId)
                statement.executeUpdate() > 0
            }
    }

    fun due(now: Instant, limit: Int = 20): List<QuizUpstreamRow> = database.withConnection { connection ->
        connection.prepareStatement(
            """SELECT * FROM quiz_upstream_sources
               WHERE enabled = TRUE
                 AND (last_checked_at IS NULL OR last_checked_at <= ?)
               ORDER BY last_checked_at ASC NULLS FIRST
               LIMIT ?"""
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now.minusSeconds(15 * 60L)))
            statement.setInt(2, limit.coerceIn(1, 200))
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val row = read(rows)
                        if (!row.nextDueAt().isAfter(now)) add(row)
                    }
                }
            }
        }
    }

    fun recordSuccess(
        parentId: String,
        checkedAt: Instant,
        status: String,
        version: Long?,
        etag: String?,
        lastModified: String?
    ) {
        database.withConnection { connection ->
            connection.prepareStatement(
                """UPDATE quiz_upstream_sources SET
                   last_checked_at = ?, last_success_at = ?, last_error = '', last_status = ?,
                   last_version = ?, etag = ?, last_modified = ?, updated_at = ?
                   WHERE parent_id = ?"""
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(checkedAt))
                statement.setTimestamp(2, Timestamp.from(checkedAt))
                statement.setString(3, status)
                if (version == null) statement.setNull(4, Types.BIGINT)
                else statement.setLong(4, version)
                statement.setString(5, etag)
                statement.setString(6, lastModified)
                statement.setTimestamp(7, Timestamp.from(checkedAt))
                statement.setString(8, parentId)
                statement.executeUpdate()
            }
        }
    }

    fun recordFailure(parentId: String, checkedAt: Instant, error: String) {
        database.withConnection { connection ->
            connection.prepareStatement(
                """UPDATE quiz_upstream_sources SET
                   last_checked_at = ?, last_error = ?, last_status = 'failed', updated_at = ?
                   WHERE parent_id = ?"""
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(checkedAt))
                statement.setString(2, error.take(UPSTREAM_MAX_ERROR_CHARS))
                statement.setTimestamp(3, Timestamp.from(checkedAt))
                statement.setString(4, parentId)
                statement.executeUpdate()
            }
        }
    }

    fun appendEvent(parentId: String, event: String, detail: String = "") {
        database.withConnection { connection ->
            connection.prepareStatement(
                "INSERT INTO quiz_upstream_events (id, parent_id, event, detail, created_at) VALUES (?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, Codes.uuid())
                statement.setString(2, parentId)
                statement.setString(3, event.take(120))
                statement.setString(4, detail.take(UPSTREAM_MAX_ERROR_CHARS))
                statement.setTimestamp(5, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
    }

    fun events(parentId: String, limit: Int = 20): List<QuizUpstreamEventView> =
        database.withConnection { connection ->
            connection.prepareStatement(
                "SELECT * FROM quiz_upstream_events WHERE parent_id = ? ORDER BY created_at DESC LIMIT ?"
            ).use { statement ->
                statement.setString(1, parentId)
                statement.setInt(2, limit.coerceIn(1, 100))
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(QuizUpstreamEventView(
                                rows.getString("id"),
                                rows.getString("event"),
                                rows.getString("detail"),
                                readInstant(rows, "created_at")!!.toString()
                            ))
                        }
                    }
                }
            }
        }

    private fun findByParent(connection: Connection, parentId: String): QuizUpstreamRow? =
        connection.prepareStatement("SELECT * FROM quiz_upstream_sources WHERE parent_id = ?")
            .use { statement ->
                statement.setString(1, parentId)
                statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
            }

    private fun read(rows: ResultSet) = QuizUpstreamRow(
        parentId = rows.getString("parent_id"),
        title = rows.getString("title"),
        url = rows.getString("url"),
        publicKey = rows.getString("public_key"),
        keyId = rows.getString("key_id"),
        enabled = rows.getBoolean("enabled"),
        intervalMinutes = rows.getInt("interval_minutes"),
        etag = rows.getString("etag"),
        lastModified = rows.getString("last_modified"),
        lastCheckedAt = readInstant(rows, "last_checked_at"),
        lastSuccessAt = readInstant(rows, "last_success_at"),
        lastError = rows.getString("last_error"),
        lastStatus = rows.getString("last_status"),
        lastVersion = rows.getObject("last_version")?.let { rows.getLong("last_version") },
        createdAt = readInstant(rows, "created_at")!!,
        updatedAt = readInstant(rows, "updated_at")!!
    )
}

class QuizUpstreamSync(
    private val repository: QuizUpstreamRepository,
    private val packs: QuizPackRepository,
    private val fetcher: QuizUpstreamFetcher
) {
    @Synchronized
    fun sync(parentId: String): QuizUpstreamSyncResult {
        val source = repository.find(parentId)
            ?: return QuizUpstreamSyncResult(false, "not_configured", null,
                "upstream-not-configured", Instant.now().toString())
        if (!source.enabled) {
            return QuizUpstreamSyncResult(false, "disabled", source.lastVersion,
                "upstream-disabled", Instant.now().toString())
        }
        val checkedAt = Instant.now()
        return try {
            val response = fetcher.fetch(source)
            when (response.statusCode) {
                304 -> success(parentId, checkedAt, "not_modified", source.lastVersion,
                    response.etag, response.lastModified)
                200 -> {
                    val body = response.body
                        ?: throw QuizUpstreamException("upstream-empty-body")
                    val content = parseUpstreamContent(body)
                    if (content.deltaEnvelope == null &&
                        QuizPackValidator.payloadSchema(content.fullEnvelope) == 2L
                    ) {
                        throw QuizUpstreamException("upstream-delta-full-target-required")
                    }
                    val verified = QuizPackValidator.verify(source.publicKey,
                        content.fullEnvelope)
                    val active = packs.findActive(parentId)
                    when {
                        active != null && verified.version < active.version ->
                            throw QuizUpstreamException("upstream-version-regression")
                        active != null && verified.version == active.version ->
                            success(parentId, checkedAt, "no_update", verified.version,
                                response.etag, response.lastModified)
                        else -> {
                            val delta = resolveDelta(parentId, source.publicKey, content,
                                verified, active)
                            val row = packs.create(parentId, source.title, verified, delta,
                                activate = true)
                            success(parentId, checkedAt, "updated", row.version,
                                response.etag, response.lastModified,
                                if (delta == null) "updated-full" else "updated-delta")
                        }
                    }
                }
                else -> throw QuizUpstreamException("upstream-http-${response.statusCode}")
            }
        } catch (exception: QuizUpstreamException) {
            failure(parentId, checkedAt, exception.code)
        } catch (exception: ApiException) {
            failure(parentId, checkedAt, "upstream-invalid-pack:${exception.message}")
        } catch (exception: RuntimeException) {
            failure(parentId, checkedAt,
                "upstream-sync-failed:${exception.javaClass.simpleName}")
        }
    }

    private fun success(
        parentId: String,
        checkedAt: Instant,
        status: String,
        version: Long?,
        etag: String?,
        lastModified: String?,
        eventStatus: String = status
    ): QuizUpstreamSyncResult {
        repository.recordSuccess(parentId, checkedAt, status, version, etag, lastModified)
        repository.appendEvent(parentId, "upstream.sync.$eventStatus", version?.toString().orEmpty())
        return QuizUpstreamSyncResult(true, status, version, "", checkedAt.toString())
    }

    private fun resolveDelta(
        parentId: String,
        publicKey: String,
        content: UpstreamContent,
        target: VerifiedQuizPack,
        active: QuizPackRow?
    ): VerifiedQuizDelta? {
        val deltaEnvelope = content.deltaEnvelope ?: return null
        val baseVersion = content.baseVersion
        if (baseVersion == null) {
            repository.appendEvent(parentId, "upstream.sync.delta-base-mismatch",
                "base version is missing; complete target pack was applied")
            return null
        }
        if (active == null || active.version != baseVersion) {
            repository.appendEvent(parentId, "upstream.sync.delta-base-mismatch",
                "base=$baseVersion active=${active?.version ?: "none"}; complete target pack was applied")
            return null
        }
        val base = packs.findVersion(parentId, baseVersion)
        if (base == null || !base.publicKey.equals(target.normalizedPublicKey, ignoreCase = true)) {
            repository.appendEvent(parentId, "upstream.sync.delta-base-mismatch",
                "base=$baseVersion signing key does not match the target; complete target pack was applied")
            return null
        }
        return try {
            QuizPackValidator.verifyDelta(publicKey, deltaEnvelope, base.envelope,
                target.envelope, target.version)
        } catch (exception: ApiException) {
            repository.appendEvent(parentId, "upstream.sync.delta-invalid",
                (exception.message ?: "invalid delta").replace('\n', ' ').take(UPSTREAM_MAX_ERROR_CHARS))
            null
        }
    }

    private fun failure(
        parentId: String,
        checkedAt: Instant,
        error: String
    ): QuizUpstreamSyncResult {
        val safeError = error.replace('\n', ' ').take(UPSTREAM_MAX_ERROR_CHARS)
        repository.recordFailure(parentId, checkedAt, safeError)
        repository.appendEvent(parentId, "upstream.sync.failed", safeError)
        return QuizUpstreamSyncResult(false, "failed", null, safeError, checkedAt.toString())
    }
}

private data class UpstreamContent(
    val fullEnvelope: String,
    val deltaEnvelope: String?,
    val baseVersion: Long?
)

private val upstreamContentJson = Json { ignoreUnknownKeys = false }

private fun parseUpstreamContent(body: String): UpstreamContent {
    val parsed = try {
        upstreamContentJson.parseToJsonElement(body)
    } catch (_: Exception) {
        return UpstreamContent(body, null, null)
    }
    val objectValue = parsed as? JsonObject ?: return UpstreamContent(body, null, null)
    val full = (objectValue["envelope"] as? JsonPrimitive)?.contentOrNull
        ?: return UpstreamContent(body, null, null)
    val delta = (objectValue["delta_envelope"] as? JsonPrimitive)?.contentOrNull
    val baseVersion = (objectValue["base_version"] as? JsonPrimitive)?.longOrNull
    return UpstreamContent(full, delta, baseVersion)
}

class QuizUpstreamScheduler(
    private val repository: QuizUpstreamRepository,
    private val sync: QuizUpstreamSync
) {
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        delay(5_000)
        while (isActive) {
            try {
                repository.due(Instant.now()).forEach { source ->
                    if (isActive) sync.sync(source.parentId)
                }
            } catch (exception: Exception) {
                // The next minute retries; the source row retains any recorded error.
            }
            delay(60_000)
        }
    }
}

fun quizUpstreamView(row: QuizUpstreamRow) = QuizUpstreamView(
    row.title,
    row.url,
    row.publicKey,
    row.keyId,
    row.enabled,
    row.intervalMinutes,
    row.lastCheckedAt?.toString(),
    row.lastSuccessAt?.toString(),
    row.lastError,
    row.lastStatus,
    row.lastVersion,
    row.createdAt.toString(),
    row.updatedAt.toString()
)

package com.guard.server

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.util.Base64

data class ServerConfig(
    val port: Int,
    val host: String,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val policySigner: PolicySigner,
    val livekitUrl: String?,
    val livekitApiKey: String?,
    val livekitApiSecret: String?,
    val livekitApiUrl: String? = null,
    val corsOrigins: List<String>,
    val pairingTtlSeconds: Long = 600,
    val parentTokenTtlSeconds: Long = 12 * 60 * 60,
    val deviceTokenTtlSeconds: Long = 30L * 24 * 60 * 60,
    val callTtlSeconds: Long = 120,
    val devMode: Boolean = false
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ServerConfig {
            val devMode = environment["GUARD_DEV_MODE"].toBoolean()
            val jwtSecret = secret(environment, "GUARD_JWT_SECRET")
                ?: if (devMode) Base64.getEncoder().encodeToString(ByteArray(48).also { SecureRandom().nextBytes(it) })
                else throw IllegalStateException("GUARD_JWT_SECRET is required")
            require(jwtSecret.length >= 32) { "GUARD_JWT_SECRET must be at least 32 characters" }

            val policyPem = secret(environment, "GUARD_POLICY_PRIVATE_KEY_PEM")?.replace("\\n", "\n")
            val policyPublicPem = secret(environment, "GUARD_POLICY_PUBLIC_KEY_PEM")?.replace("\\n", "\n")
            val policySigner = when {
                !policyPem.isNullOrBlank() && !policyPublicPem.isNullOrBlank() ->
                    PolicySigner.fromPkcs8Pem(policyPem, policyPublicPem)
                devMode -> PolicySigner.generate()
                else -> throw IllegalStateException(
                    "GUARD_POLICY_PRIVATE_KEY_PEM and GUARD_POLICY_PUBLIC_KEY_PEM are required"
                )
            }
            val databaseUrl = environment["GUARD_DATABASE_URL"]
                ?: "jdbc:postgresql://localhost:5432/guard"
            return ServerConfig(
                port = environment["GUARD_PORT"]?.toIntOrNull() ?: 8080,
                host = environment["GUARD_HOST"] ?: "0.0.0.0",
                databaseUrl = databaseUrl,
                databaseUser = environment["GUARD_DATABASE_USER"] ?: "guard",
                databasePassword = secret(environment, "GUARD_DATABASE_PASSWORD") ?: "guard",
                jwtSecret = jwtSecret,
                policySigner = policySigner,
                livekitUrl = environment["GUARD_LIVEKIT_URL"]?.takeIf { it.isNotBlank() },
                livekitApiKey = secret(environment, "GUARD_LIVEKIT_API_KEY"),
                livekitApiSecret = secret(environment, "GUARD_LIVEKIT_API_SECRET"),
                livekitApiUrl = (environment["GUARD_LIVEKIT_API_URL"]
                    ?: environment["GUARD_LIVEKIT_URL"])?.takeIf { it.isNotBlank() },
                corsOrigins = corsOrigins(environment),
                devMode = devMode
            )
        }

        /**
         * Comma-separated browser origins. Placeholder values such as the
         * compose expansion of an unset domain ("https://") are ignored, and
         * local development falls back to the two localhost console origins.
         */
        private fun corsOrigins(environment: Map<String, String>): List<String> {
            val configured = (environment["GUARD_CORS_ORIGINS"] ?: "")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .filter(::isValidCorsOrigin)
            return configured.ifEmpty { listOf("http://localhost:5173", "https://localhost") }
        }

        private fun isValidCorsOrigin(value: String): Boolean = try {
            val uri = URI(value)
            (uri.scheme == "http" || uri.scheme == "https") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null
        } catch (exception: Exception) {
            false
        }

        /** Accepts either an inline secret or a mounted *_FILE secret. */
        private fun secret(environment: Map<String, String>, name: String): String? {
            val inline = environment[name]?.takeIf { it.isNotBlank() }
            if (inline != null) return inline
            val file = environment["${name}_FILE"]?.takeIf { it.isNotBlank() } ?: return null
            return try {
                Files.readString(Path.of(file), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("${name}_FILE is empty")
            } catch (exception: Exception) {
                throw IllegalStateException("Unable to read ${name}_FILE", exception)
            }
        }
    }
}

object DevelopmentPolicyKey {
    fun generatePem(): String {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        val pair = generator.generateKeyPair()
        return "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
    }
}

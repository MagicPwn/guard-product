package com.guard.server

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

class PolicySigner private constructor(
    private val privateKey: PrivateKey,
    val publicKey: PublicKey
) {
    val keyId: String = keyId(publicKey)
    val publicKeyBase64: String = Base64.getEncoder().encodeToString(publicKey.encoded)

    fun sign(payload: String): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(payload.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    companion object {
        fun generate(): PolicySigner {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(256)
            val pair = generator.generateKeyPair()
            return PolicySigner(pair.private, pair.public)
        }

        fun fromPkcs8Pem(privatePem: String, publicPem: String): PolicySigner {
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(
                PKCS8EncodedKeySpec(decodePem(privatePem, "PRIVATE KEY"))
            )
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                java.security.spec.X509EncodedKeySpec(decodePem(publicPem, "PUBLIC KEY"))
            )
            return PolicySigner(privateKey, publicKey)
        }

        private fun decodePem(pem: String, label: String): ByteArray {
            val base64 = pem
                .replace("-----BEGIN $label-----", "")
                .replace("-----END $label-----", "")
                .replace("\\s".toRegex(), "")
            return Base64.getDecoder().decode(base64)
        }
    }
}

fun keyId(publicKey: PublicKey): String = digestHex("SHA-256", publicKey.encoded)

fun canonicalJson(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries.sortedBy { it.key }
        .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            JsonPrimitive(key).toString() + ":" + canonicalJson(value)
        }
    is JsonArray -> element.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(it) }
    is JsonNull -> "null"
    is JsonPrimitive -> element.toString()
}

fun digestHex(algorithm: String, bytes: ByteArray): String {
    return MessageDigest.getInstance(algorithm).digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

/** Verifies the proof produced by the P-256 key stored in Android Keystore. */
fun verifyDeviceSignature(publicKeyBase64: String, message: String, signatureBase64: String): Boolean {
    return try {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64))
        )
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(message.toByteArray(Charsets.UTF_8))
        verifier.verify(Base64.getDecoder().decode(signatureBase64))
    } catch (exception: Exception) {
        false
    }
}

/** Bounds replay of short-lived device proofs without persisting nonces. */
class ReplayGuard(private val ttlSeconds: Long = 360) {
    private val seen = ConcurrentHashMap<String, Long>()

    fun accept(nonce: String): Boolean {
        val now = System.currentTimeMillis()
        seen.entries.removeIf { now - it.value > ttlSeconds * 1000 }
        return seen.putIfAbsent(nonce, now) == null
    }
}

class PasswordHasher(
    private val iterations: Int = 210_000,
    private val random: SecureRandom = SecureRandom()
) {
    fun hash(password: String): String {
        require(password.length in 10..256) { "Password must contain 10 to 256 characters" }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val derived = derive(password, salt, iterations)
        val encoder = Base64.getEncoder()
        return "pbkdf2-sha256\$$iterations\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(derived)}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        return try {
            val rounds = parts[1].toInt()
            if (rounds !in 10_000..1_000_000) return false
            val salt = Base64.getDecoder().decode(parts[2])
            val expected = Base64.getDecoder().decode(parts[3])
            val actual = derive(password, salt, rounds, expected.size)
            MessageDigest.isEqual(expected, actual)
        } catch (exception: IllegalArgumentException) {
            false
        }
    }

    private fun derive(password: String, salt: ByteArray, rounds: Int, size: Int = 32): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, rounds, size * 8)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }
}

class TokenService(private val secret: String) {
    private val algorithm = Algorithm.HMAC256(secret)
    private val parentVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer("guard-server").withAudience("parent").build()
    private val deviceVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer("guard-server").withAudience("device").build()

    fun parentToken(parentId: String, sessionId: String, ttlSeconds: Long): String =
        JWT.create().withIssuer("guard-server").withAudience("parent")
            .withSubject(parentId).withClaim("role", "parent")
            .withClaim("sid", sessionId)
            .withIssuedAt(Date()).withExpiresAt(Date(System.currentTimeMillis() + ttlSeconds * 1000))
            .sign(algorithm)

    fun deviceToken(deviceId: String, ttlSeconds: Long): String =
        JWT.create().withIssuer("guard-server").withAudience("device")
            .withSubject(deviceId).withClaim("role", "device")
            .withIssuedAt(Date()).withExpiresAt(Date(System.currentTimeMillis() + ttlSeconds * 1000))
            .sign(algorithm)

    fun verifyParent(token: String): ParentIdentity? = try {
        val decoded = parentVerifier.verify(token)
        val sessionId = decoded.getClaim("sid").asString()
        if (sessionId.isNullOrBlank()) null
        else ParentIdentity(decoded.subject, sessionId)
    } catch (exception: Exception) {
        null
    }

    fun verifyDevice(token: String): String? = try {
        deviceVerifier.verify(token).subject
    } catch (exception: JWTVerificationException) {
        null
    }
}

data class ParentIdentity(val parentId: String, val sessionId: String)

object Codes {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun pairingCode(): String = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    fun pairingHash(code: String): String = digestHex("SHA-256", code.trim().uppercase().toByteArray())
    fun now(): Instant = Instant.now()
    fun uuid(): String = java.util.UUID.randomUUID().toString()
}

class LiveKitTokenService(
    private val url: String,
    private val apiKey: String,
    private val apiSecret: String
) {
    fun issue(identity: String, room: String): LiveKitToken {
        val expiresAt = System.currentTimeMillis() + 15 * 60 * 1000
        val token = JWT.create()
            .withIssuer(apiKey)
            .withSubject(identity)
            .withIssuedAt(Date())
            .withExpiresAt(Date(expiresAt))
            .withClaim("video", mapOf(
                "roomJoin" to true,
                "room" to room,
                "canPublish" to true,
                "canSubscribe" to true
            ))
            .sign(Algorithm.HMAC256(apiSecret))
        return LiveKitToken(url, token, room, expiresAt)
    }
}

data class LiveKitToken(val url: String, val token: String, val room: String, val expiresAt: Long)

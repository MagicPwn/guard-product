package com.guard.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigTest {
    @Test
    fun secretsCanBeMountedAsFiles() {
        val directory = Files.createTempDirectory("guard-config-test")
        val jwt = directory.resolve("jwt")
        val database = directory.resolve("database")
        val livekitKey = directory.resolve("livekit-key")
        val livekitSecret = directory.resolve("livekit-secret")
        Files.writeString(jwt, "a".repeat(48))
        Files.writeString(database, "database-secret")
        Files.writeString(livekitKey, "livekit-key")
        Files.writeString(livekitSecret, "livekit-secret")
        try {
            val config = ServerConfig.fromEnvironment(mapOf(
                "GUARD_DEV_MODE" to "true",
                "GUARD_JWT_SECRET_FILE" to jwt.toString(),
                "GUARD_DATABASE_PASSWORD_FILE" to database.toString(),
                "GUARD_LIVEKIT_URL" to "wss://livekit.example.test",
                "GUARD_LIVEKIT_API_KEY_FILE" to livekitKey.toString(),
                "GUARD_LIVEKIT_API_SECRET_FILE" to livekitSecret.toString()
            ))
            assertEquals("a".repeat(48), config.jwtSecret)
            assertEquals("database-secret", config.databasePassword)
            assertEquals("wss://livekit.example.test", config.livekitUrl)
            assertEquals("livekit-key", config.livekitApiKey)
            assertEquals("livekit-secret", config.livekitApiSecret)
            assertNotNull(config.policySigner.publicKey)
        } finally {
            Files.deleteIfExists(jwt)
            Files.deleteIfExists(database)
            Files.deleteIfExists(livekitKey)
            Files.deleteIfExists(livekitSecret)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun emptyOrPlaceholderCorsOriginsFallBackToLocalConsole() {
        val placeholder = ServerConfig.fromEnvironment(mapOf(
            "GUARD_DEV_MODE" to "true",
            "GUARD_CORS_ORIGINS" to "https://"
        ))
        assertEquals(listOf("http://localhost:5173", "https://localhost"),
            placeholder.corsOrigins)

        val configured = ServerConfig.fromEnvironment(mapOf(
            "GUARD_DEV_MODE" to "true",
            "GUARD_CORS_ORIGINS" to " https://guard.example.test , https:// "
        ))
        assertEquals(listOf("https://guard.example.test"), configured.corsOrigins)
        assertTrue(configured.corsOrigins.none { it == "https://" })
    }

    @Test
    fun liveKitWebSocketUrlMapsToRoomServiceApi() {
        assertEquals("https://livekit.example.test/",
            liveKitRoomServiceUrl("wss://livekit.example.test"))
        assertEquals("http://127.0.0.1:7880/",
            liveKitRoomServiceUrl("ws://127.0.0.1:7880"))
        assertEquals("https://livekit.example.test/",
            liveKitRoomServiceUrl("https://livekit.example.test"))
        assertFailsWith<IllegalArgumentException> {
            liveKitRoomServiceUrl("ftp://livekit.example.test")
        }
    }

    @Test
    fun missingLiveKitRoomIsAnIdempotentDeleteSuccess() {
        assertTrue(isMissingLiveKitRoom(404,
            """{"code":"not_found","msg":"requested room does not exist"}"""))
        assertTrue(!isMissingLiveKitRoom(404, "route not found"))
        assertTrue(!isMissingLiveKitRoom(500,
            """{"code":"not_found","msg":"requested room does not exist"}"""))
    }
}

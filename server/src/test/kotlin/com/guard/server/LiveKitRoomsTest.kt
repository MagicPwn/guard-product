package com.guard.server

import com.auth0.jwt.JWT
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LiveKitRoomsTest {
    @Test
    fun officialSdkUsesTwirpDeleteRoomWithRoomAndBearerAuth() {
        val paths = mutableListOf<String>()
        val authorizations = mutableListOf<String>()
        val bodies = mutableListOf<ByteArray>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/twirp/livekit.RoomService/DeleteRoom") { exchange ->
            paths += exchange.requestURI.path
            authorizations += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val body = exchange.requestBody.readBytes()
            bodies += body
            val room = body.toString(Charsets.UTF_8)
            when {
                room.contains("missing-room") -> {
                    val response = """{"code":"not_found","msg":"requested room does not exist"}"""
                        .toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(404, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                room.contains("fail-room") -> {
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                }
                else -> {
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
            }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
        try {
            val service = LiveKitServerRoomService(
                "http://127.0.0.1:${server.address.port}", "test-key", "test-secret"
            )
            service.deleteRoom("guard-device-call")
            service.deleteRoom("missing-room")
            assertFailsWith<LiveKitRoomDeletionException> { service.deleteRoom("fail-room") }

            assertEquals(List(3) { "/twirp/livekit.RoomService/DeleteRoom" }, paths)
            assertEquals(3, bodies.size)
            assertTrue(bodies[0].toString(Charsets.UTF_8).contains("guard-device-call"))
            authorizations.forEach { authorization ->
                assertTrue(authorization.startsWith("Bearer "))
                assertEquals("test-key", JWT.decode(authorization.removePrefix("Bearer ")).issuer)
            }
        } finally {
            server.stop(0)
            (server.executor as ExecutorService).shutdownNow()
        }
    }
}

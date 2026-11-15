package com.guard.server

import io.livekit.server.RoomServiceClient
import org.slf4j.LoggerFactory
import java.net.URI

interface LiveKitRoomService {
    fun deleteRoom(roomName: String)
}

class LiveKitRoomDeletionException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class LiveKitServerRoomService(
    clientUrl: String,
    apiKey: String,
    apiSecret: String
) : LiveKitRoomService {
    private val log = LoggerFactory.getLogger(LiveKitServerRoomService::class.java)
    private val client = RoomServiceClient.createClient(
        host = liveKitRoomServiceUrl(clientUrl),
        apiKey = apiKey,
        secret = apiSecret
    )

    override fun deleteRoom(roomName: String) {
        val response = try {
            client.deleteRoom(roomName).execute()
        } catch (exception: Exception) {
            log.warn("LiveKit room deletion request failed for {}: {}",
                roomName, exception.javaClass.simpleName)
            throw LiveKitRoomDeletionException("LiveKit room deletion request failed", exception)
        }
        if (!response.isSuccessful) {
            val body = try {
                response.errorBody()?.string()
            } catch (exception: Exception) {
                null
            }
            if (isMissingLiveKitRoom(response.code(), body)) return
            log.warn("LiveKit refused room deletion for {} with HTTP {}", roomName, response.code())
            throw LiveKitRoomDeletionException(
                "LiveKit room deletion failed with HTTP ${response.code()}"
            )
        }
    }
}

fun isMissingLiveKitRoom(statusCode: Int, errorBody: String?): Boolean =
    statusCode == 404 && errorBody?.contains("not_found", ignoreCase = true) == true

fun liveKitRoomServiceUrl(clientUrl: String): String {
    val uri = try {
        URI(clientUrl)
    } catch (exception: Exception) {
        throw IllegalArgumentException("LiveKit URL is invalid", exception)
    }
    val scheme = when (uri.scheme?.lowercase()) {
        "wss", "https" -> "https"
        "ws", "http" -> "http"
        else -> throw IllegalArgumentException("LiveKit URL must use ws, wss, http, or https")
    }
    val authority = uri.rawAuthority
        ?: throw IllegalArgumentException("LiveKit URL must include a host")
    return "$scheme://$authority/"
}

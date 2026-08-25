package com.relay.call.output.sfu

import com.relay.call.service.sfu.RoomDirectory

import io.livekit.server.RoomServiceClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class LivekitRoomDirectory(
    private val roomServiceClient: RoomServiceClient
) : RoomDirectory {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun participantIdentities(room: String): Set<String>? {
        val response = roomServiceClient.listParticipants(room).execute()
        if (!response.isSuccessful) {
            if (response.code() == 404) return null
            throw IllegalStateException(
                "LiveKit listParticipants($room) failed: ${response.code()} ${response.message()}"
            )
        }
        val participants = response.body() ?: return null
        return participants.map { it.identity }.toSet()
    }

    override fun closeRoom(room: String) {
        val response = roomServiceClient.deleteRoom(room).execute()
        if (!response.isSuccessful) {
            logger.warn("LiveKit deleteRoom({}) answered {} {}", room, response.code(), response.message())
        }
    }
}

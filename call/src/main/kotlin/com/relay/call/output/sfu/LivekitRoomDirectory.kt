package com.relay.call.output.sfu

import com.relay.call.service.sfu.RoomDirectory

import io.livekit.server.RoomServiceClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The LiveKit RoomService API, blocking-style. `execute()` on the SDK's retrofit calls parks the
 * virtual thread like any other blocking I/O — no reactive machinery involved.
 *
 * A room LiveKit does not know is reported as null rather than an error, because a finished room
 * genuinely stops existing: LiveKit's `list` simply returns nothing for it, and reconciliation
 * needs to tell "empty room" apart from "the API call failed".
 */
@Component
class LivekitRoomDirectory(
    private val roomServiceClient: RoomServiceClient
) : RoomDirectory {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun participantIdentities(room: String): Set<String>? {
        val response = roomServiceClient.listParticipants(room).execute()
        if (!response.isSuccessful) {
            // LiveKit answers twirp-style errors with non-2xx; a missing room is one of them.
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
            // Best-effort by contract: the empty-room timeout cleans up whatever this misses.
            logger.warn("LiveKit deleteRoom({}) answered {} {}", room, response.code(), response.message())
        }
    }
}

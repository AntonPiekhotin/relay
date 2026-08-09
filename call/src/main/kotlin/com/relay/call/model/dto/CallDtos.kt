package com.relay.call.model.dto

import java.time.Instant

/** The state of one call, as returned to the gateway on the internal signaling endpoints. */
data class CallResponse(
    val id: String,
    val dialogId: String?,
    val initiator: String,
    val participantIds: List<String>,
    val media: String,
    val status: String,
    val startedAt: Instant,
    val answeredAt: Instant?,
    val endedAt: Instant?,
    val durationSeconds: Int?,
    val endReason: String?
)

/**
 * One row of the call log. [direction] and [peerId] are relative to whoever asked — the same call
 * is outgoing for one participant and incoming for the other.
 */
data class CallHistoryEntry(
    val id: String,
    val dialogId: String?,
    val direction: String,
    val peerId: String?,
    val media: String,
    val status: String,
    val startedAt: Instant,
    val answeredAt: Instant?,
    val endedAt: Instant?,
    val durationSeconds: Int?,
    val endReason: String?
) {
    companion object {
        const val OUTGOING = "outgoing"
        const val INCOMING = "incoming"
    }
}

/**
 * A page of call history. [nextCursor] is the id to pass as `before` for the following page, and is
 * null on the last page. Cursor rather than offset, because calls insert at the head.
 */
data class CallHistoryResponse(
    val calls: List<CallHistoryEntry>,
    val nextCursor: String?
)

/**
 * An `RTCIceServer`, shaped so a client can hand it to `RTCPeerConnection` unchanged.
 * STUN needs no credentials; TURN gets a short-lived pair.
 */
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

/** [ttlSeconds] is how long the TURN credentials stay valid — refetch before it elapses. */
data class IceServersResponse(
    val iceServers: List<IceServer>,
    val ttlSeconds: Long
)

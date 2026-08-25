package com.relay.call.model.dto

import java.time.Instant

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

data class CallHistoryEntry(
    val id: String,
    val dialogId: String?,
    val kind: String,
    val direction: String,
    val peerId: String?,
    val participantCount: Int,
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

data class CallHistoryResponse(
    val calls: List<CallHistoryEntry>,
    val nextCursor: String?
)

data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

data class IceServersResponse(
    val iceServers: List<IceServer>,
    val ttlSeconds: Long
)

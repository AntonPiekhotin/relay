package com.relay.call.util.mapper

import com.relay.call.model.Call
import com.relay.call.model.CallParticipant
import com.relay.call.model.dto.CallHistoryEntry
import com.relay.call.model.dto.CallResponse

fun Call.toResponse(participants: List<CallParticipant>): CallResponse = CallResponse(
    id = id.toString(),
    dialogId = dialogId?.toString(),
    initiator = initiator,
    participantIds = participants.map { it.userId },
    media = media.wireValue,
    status = status.wireValue,
    startedAt = startedAt,
    answeredAt = answeredAt,
    endedAt = endedAt,
    durationSeconds = durationSeconds,
    endReason = endReason
)

/**
 * [viewerId] decides direction and peer: the same row is an outgoing call for the initiator and an
 * incoming one for everybody else.
 */
fun Call.toHistoryEntry(participants: List<CallParticipant>, viewerId: String): CallHistoryEntry =
    CallHistoryEntry(
        id = id.toString(),
        dialogId = dialogId?.toString(),
        direction = if (initiator == viewerId) CallHistoryEntry.OUTGOING else CallHistoryEntry.INCOMING,
        peerId = participants.map { it.userId }.firstOrNull { it != viewerId },
        media = media.wireValue,
        status = status.wireValue,
        startedAt = startedAt,
        answeredAt = answeredAt,
        endedAt = endedAt,
        durationSeconds = durationSeconds,
        endReason = endReason
    )

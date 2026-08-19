package com.relay.call.util.mapper

import com.relay.call.model.Call
import com.relay.call.model.CallKind
import com.relay.call.model.CallParticipant
import com.relay.call.model.dto.CallHistoryEntry
import com.relay.call.model.dto.CallResponse
import com.relay.call.model.dto.GroupCallParticipantView
import com.relay.call.model.dto.GroupCallResponse
import com.relay.call.model.dto.SfuAccess
import com.relay.call.service.sfu.RoomToken
import java.time.Instant

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
 * incoming one for everybody else. A group call has no single peer, so [CallHistoryEntry.peerId]
 * is the initiator there (or null when the viewer *is* the initiator) and the roster is summarised
 * as a count.
 */
fun Call.toHistoryEntry(participants: List<CallParticipant>, viewerId: String): CallHistoryEntry =
    CallHistoryEntry(
        id = id.toString(),
        dialogId = dialogId?.toString(),
        kind = kind.wireValue,
        direction = if (initiator == viewerId) CallHistoryEntry.OUTGOING else CallHistoryEntry.INCOMING,
        peerId = when (kind) {
            CallKind.DIRECT -> participants.map { it.userId }.firstOrNull { it != viewerId }
            CallKind.GROUP -> initiator.takeIf { it != viewerId }
        },
        participantCount = participants.size,
        media = media.wireValue,
        status = status.wireValue,
        startedAt = startedAt,
        answeredAt = answeredAt,
        endedAt = endedAt,
        durationSeconds = durationSeconds,
        endReason = endReason
    )

fun RoomToken.toSfuAccess(): SfuAccess = SfuAccess(url = url, token = token, expiresAt = expiresAt)

/**
 * The group-call REST shape. [livekit] stays the caller's decision: only create and join may admit
 * anyone to the room.
 */
fun Call.toGroupResponse(
    participants: List<CallParticipant>,
    ringExpiresAt: Instant,
    livekit: SfuAccess?
): GroupCallResponse = GroupCallResponse(
    callId = id.toString(),
    kind = kind.wireValue,
    media = media.wireValue,
    status = status.wireValue,
    initiator = initiator,
    startedAt = startedAt,
    ringExpiresAt = ringExpiresAt,
    answeredAt = answeredAt,
    endedAt = endedAt,
    endReason = endReason,
    durationSeconds = durationSeconds,
    participants = participants.map { GroupCallParticipantView(it.userId, it.state.wireValue) },
    livekit = livekit
)

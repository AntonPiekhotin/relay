package com.relay.call.model.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import java.time.Instant

/**
 * Group-call REST bodies. Client-facing (camelCase, like every REST surface), unlike the direct
 * call's DTOs in `common` — those exist because the *gateway* is the caller on the frame path;
 * group calls go client → api-gateway → here, and no other service needs these shapes.
 *
 * [sessionId] is optional and only excludes the acting device from the `cancel` sent to the user's
 * other devices. A client knows its own from `session.connected`. Absent, the cancel reaches every
 * device including the acting one — harmless, since that device is showing an in-call UI, not a
 * ringing one.
 */
data class CreateGroupCallRequest(

    /**
     * Client-generated, like the direct call's — a retried create with the same id is the same
     * call, answered with its current state instead of a duplicate.
     */
    @field:NotBlank
    val callId: String,

    @field:NotBlank
    @field:Pattern(regexp = "audio|video", message = "media must be 'audio' or 'video'")
    val media: String,

    @field:NotEmpty
    val inviteeIds: List<String>,

    val sessionId: String? = null
)

data class JoinGroupCallRequest(
    val sessionId: String? = null
)

data class DeclineGroupCallRequest(
    val reason: String? = null,
    val sessionId: String? = null
)

data class LeaveGroupCallRequest(
    val sessionId: String? = null
)

data class GroupCallParticipantView(
    val userId: String,
    val state: String
)

/** Where to connect and the proof this user may — see `RoomTokenFactory`. */
data class SfuAccess(
    val url: String,
    val token: String,
    val expiresAt: Instant
)

/**
 * Whether create actually created — the controller's 201-versus-200 split, decided where the fact
 * is known. Same pattern as user-service's `AddContactResult`.
 */
data class CreateGroupCallResult(
    val created: Boolean,
    val response: GroupCallResponse
)

/**
 * The state of one group call. [livekit] is present only on the responses that admit the caller to
 * the room — create and join — and null everywhere else: reading a call's state must not mint
 * credentials for it.
 */
data class GroupCallResponse(
    val callId: String,
    val kind: String,
    val media: String,
    val status: String,
    val initiator: String,
    val startedAt: Instant,
    val ringExpiresAt: Instant,
    val answeredAt: Instant?,
    val endedAt: Instant?,
    val endReason: String?,
    val durationSeconds: Int?,
    val participants: List<GroupCallParticipantView>,
    val livekit: SfuAccess?
)

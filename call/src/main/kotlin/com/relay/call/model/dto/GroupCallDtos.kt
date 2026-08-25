package com.relay.call.model.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import java.time.Instant

data class CreateGroupCallRequest(

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

data class SfuAccess(
    val url: String,
    val token: String,
    val expiresAt: Instant
)

data class CreateGroupCallResult(
    val created: Boolean,
    val response: GroupCallResponse
)

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

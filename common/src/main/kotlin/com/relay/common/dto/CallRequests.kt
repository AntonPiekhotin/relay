package com.relay.common.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

private const val MEDIA_PATTERN = "audio|video"

data class InviteCallRequest(

    @field:NotBlank
    @field:Size(max = 64)
    val callId: String,

    @field:NotBlank
    @field:Size(max = 64)
    val callerId: String,

    @field:NotBlank
    @field:Size(max = 64)
    val calleeId: String,

    @field:NotBlank
    @field:Size(max = 64)
    val sessionId: String,

    @field:Pattern(regexp = MEDIA_PATTERN)
    val media: String,

    @field:NotBlank
    val sdp: String,

    val dialogId: String? = null
)

data class AcceptCallRequest(

    @field:NotBlank
    @field:Size(max = 64)
    val userId: String,

    @field:NotBlank
    @field:Size(max = 64)
    val sessionId: String,

    /** The callee's SDP answer. */
    @field:NotBlank
    val sdp: String
)

data class RejectCallRequest(

    @field:NotBlank
    @field:Size(max = 64)
    val userId: String,

    @field:NotBlank
    @field:Size(max = 64)
    val sessionId: String,

    @field:Size(max = 32)
    val reason: String? = null
)

data class IceCandidateRequest(

    @field:NotBlank
    @field:Size(max = 64)
    val userId: String,

    @field:NotBlank
    @field:Size(max = 64)
    val sessionId: String,

    @field:NotEmpty
    val candidate: Map<String, Any?>
)

data class HangupCallRequest(

    @field:NotBlank
    @field:Size(max = 64)
    val userId: String,

    @field:NotBlank
    @field:Size(max = 64)
    val sessionId: String,

    @field:Size(max = 32)
    val reason: String? = null
)

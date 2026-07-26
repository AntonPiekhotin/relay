package com.relay.common.event

/**
 * Published by call-service when a WebRTC signal must reach a participant.
 *
 * [signal] is an opaque passthrough (offer / answer / ICE candidate): the gateway relays it
 * verbatim, and call state lives in call-service rather than here.
 */
data class CallSignalEvent(
    val callId: String,
    val fromUserId: String,
    val signal: Map<String, Any?>,
    val recipientIds: List<String>
)
package com.relay.common.event

/**
 * Published by call-service when a WebRTC signal must reach a participant.
 *
 * [signal] is an opaque passthrough (offer / answer / ICE candidate): the gateway relays it
 * verbatim, and call state lives in call-service rather than here. The verb lives inside it —
 * `invite`, `accept`, `reject`, `ice`, `hangup`, `cancel`, `busy`, `missed` — which is what keeps
 * the client-facing frame catalogue from growing a type per verb.
 *
 * [excludeSessionIds] names connections that must *not* receive this signal, and exists for one
 * case: when a user answers on one device, their other devices get `cancel` and the answering
 * device must not. It mirrors how the message path excludes the sending session from its own
 * `message.new` fan-out.
 */
data class CallSignalEvent(
    val callId: String,
    val fromUserId: String,
    val signal: Map<String, Any?>,
    val recipientIds: List<String>,
    val excludeSessionIds: List<String> = emptyList()
)
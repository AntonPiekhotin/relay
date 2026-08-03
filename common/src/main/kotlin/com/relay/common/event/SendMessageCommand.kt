package com.relay.common.event

/**
 * A client send, published by websocket-gateway to `messages.incoming` and consumed by
 * message-service. Keyed by [dialogId].
 *
 * [clientMessageId] is the envelope `id` the client generated; together with [senderId] it is
 * the idempotency key, so a retry of the same send is recognised rather than stored twice.
 *
 * [senderId] is set by the gateway from the authenticated session, never from the frame.
 *
 * [senderSessionId] identifies the exact connection that sent, so the resulting ack or error
 * can be routed back to that device and not to the sender's other sessions.
 */
data class SendMessageCommand(
    val clientMessageId: String,
    val dialogId: String,
    val senderId: String,
    val senderSessionId: String,
    val text: String
)
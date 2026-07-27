package com.relay.websocket.protocol

import java.time.Instant

/**
 * Error codes carried in `error` frames. Strings on the wire, so services can introduce codes
 * (e.g. DIALOG_NOT_FOUND) without the gateway enumerating them — see [OutboundFrame.Error].
 */
object ErrorCodes {
    /** Frame was not valid JSON, missing envelope fields, or carried an unknown `type`. */
    const val BAD_FRAME = "BAD_FRAME"

    /** Envelope `v` is a protocol version this gateway does not speak. */
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"

    /** The send could not be handed off. Retry the same id over REST. */
    const val SEND_FAILED = "SEND_FAILED"
}

/**
 * Gateway to client, wrapped into the envelope (ARCHITECTURE.md §10.1) by [FrameCodec] with a
 * server-assigned `ts`.
 */
sealed interface OutboundFrame {

    /**
     * First frame on every accepted socket. Not in the spec's §10.2 catalogue — a deliberate
     * extension so the client can confirm who it is connected as (documented in the spec's
     * frame table as part of this migration).
     */
    data class SessionConnected(val userId: String, val sessionId: String) : OutboundFrame

    data class Pong(val refId: String? = null) : OutboundFrame

    /** Confirms a `message.send` was stored; correlated by [clientMsgId] (§20.1 step 7). */
    data class Ack(
        val clientMsgId: String,
        val messageId: String,
        val createdAt: Instant
    ) : OutboundFrame

    data class MessageNew(
        val messageId: String,
        val dialogId: String,
        val senderId: String,
        val text: String,
        val createdAt: Instant
    ) : OutboundFrame

    data class Notification(
        val notificationId: String,
        val kind: String,
        val data: Map<String, Any?>,
        val createdAt: Instant
    ) : OutboundFrame

    data class CallSignal(
        val callId: String,
        val fromUserId: String,
        val signal: Map<String, Any?>
    ) : OutboundFrame

    /** [refId] echoes the envelope `id` of the frame that caused the error (§10.3). */
    data class Error(
        val code: String,
        val message: String,
        val refId: String? = null
    ) : OutboundFrame
}
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

    /**
     * A call signal could not be delivered to call-service — it was unreachable, timed out, or
     * failed in a way that has no more specific code. The call cannot proceed; tear down the
     * peer connection rather than retrying a signal whose ordering has already been lost.
     */
    const val CALL_SIGNAL_FAILED = "CALL_SIGNAL_FAILED"
}

/**
 * Gateway to client, wrapped into the envelope by [FrameCodec] with a
 * server-assigned `ts`.
 */
sealed interface OutboundFrame {

    /**
     * First frame on every accepted socket, so the client can confirm who it is connected as.
     * Part of the wire contract — see the frame catalogue in `docs/PROTOCOL.md`.
     */
    data class SessionConnected(val userId: String, val sessionId: String) : OutboundFrame

    data class Pong(val refId: String? = null) : OutboundFrame

    /** Confirms a `message.send` was stored; correlated by [clientMsgId]. */
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

    /** [refId] echoes the envelope `id` of the frame that caused the error. */
    data class Error(
        val code: String,
        val message: String,
        val refId: String? = null
    ) : OutboundFrame
}
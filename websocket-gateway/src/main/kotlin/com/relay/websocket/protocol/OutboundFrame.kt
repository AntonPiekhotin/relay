package com.relay.websocket.protocol

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

enum class ErrorCode {
    /** Frame was not valid JSON, or carried an unknown `type`. */
    BAD_FRAME,

    /** Frame was understood but the gateway could not act on it. */
    INTERNAL,

    /** The send could not be stored. The client should retry the same clientMessageId over REST. */
    SEND_FAILED
}

/** Gateway to client. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OutboundFrame.Connected::class, name = "CONNECTED"),
    JsonSubTypes.Type(value = OutboundFrame.Pong::class, name = "PONG"),
    JsonSubTypes.Type(value = OutboundFrame.MessageAck::class, name = "MESSAGE_ACK"),
    JsonSubTypes.Type(value = OutboundFrame.MessageNew::class, name = "MESSAGE_NEW"),
    JsonSubTypes.Type(value = OutboundFrame.Notification::class, name = "NOTIFICATION"),
    JsonSubTypes.Type(value = OutboundFrame.CallSignal::class, name = "CALL_SIGNAL"),
    JsonSubTypes.Type(value = OutboundFrame.Error::class, name = "ERROR")
)
sealed interface OutboundFrame {

    /** First frame on every accepted socket, so the client can confirm who it is connected as. */
    data class Connected(val userId: String, val sessionId: String) : OutboundFrame

    data class Pong(val nonce: String? = null) : OutboundFrame

    /**
     * Confirms a MESSAGE_SEND was stored. The client keys off [clientMessageId] to mark its
     * pending send as delivered; not receiving this is its cue to retry over REST.
     */
    data class MessageAck(
        val clientMessageId: String,
        val id: String,
        val chatId: String,
        val sentAt: Instant
    ) : OutboundFrame

    data class MessageNew(
        val id: String,
        val chatId: String,
        val senderId: String,
        val body: String,
        val sentAt: Instant,
        val clientMessageId: String? = null
    ) : OutboundFrame

    data class Notification(
        val id: String,
        val kind: String,
        val payload: Map<String, Any?>,
        val createdAt: Instant
    ) : OutboundFrame

    data class CallSignal(
        val callId: String,
        val fromUserId: String,
        val signal: Map<String, Any?>
    ) : OutboundFrame

    /**
     * [clientMessageId] is echoed when the failure can be tied to a specific client send, so the
     * client knows which message to retry over REST.
     */
    data class Error(
        val code: ErrorCode,
        val message: String,
        val clientMessageId: String? = null
    ) : OutboundFrame
}
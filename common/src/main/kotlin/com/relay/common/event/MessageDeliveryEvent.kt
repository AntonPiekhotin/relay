package com.relay.common.event

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

/**
 * Outcome of a send, published by message-service to `messages.delivery` and consumed by every
 * websocket-gateway instance: every node sees every outcome and delivers to whatever sessions it
 * holds, which is what per-instance consumer groups buy.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "outcome")
@JsonSubTypes(
    JsonSubTypes.Type(value = MessageDeliveryEvent.Accepted::class, name = "ACCEPTED"),
    JsonSubTypes.Type(value = MessageDeliveryEvent.Rejected::class, name = "REJECTED")
)
sealed interface MessageDeliveryEvent {

    /**
     * The message exists in the database. The gateway acks [senderSessionId] and pushes
     * `message.new` to every session of [recipientIds] except the acked one.
     *
     * [recipientIds] includes the sender — their other devices need the message too. It is
     * carried in the event because producers own the fan-out list: the gateway must never
     * resolve dialog membership itself.
     *
     * [duplicate] is true when this send was recognised as a retry of an already-stored message;
     * the gateway then acks the sender but does not fan out `message.new` a second time.
     */
    data class Accepted(
        val messageId: String,
        val dialogId: String,
        val senderId: String,
        val senderSessionId: String?,
        val text: String,
        val sentAt: Instant,
        val recipientIds: List<String>,
        val clientMessageId: String,
        val duplicate: Boolean = false
    ) : MessageDeliveryEvent

    /** The send was refused; the gateway sends an `error` frame with [code] to the sender. */
    data class Rejected(
        val clientMessageId: String,
        val senderId: String,
        val senderSessionId: String?,
        val code: String,
        val reason: String
    ) : MessageDeliveryEvent
}
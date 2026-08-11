package com.relay.common.event

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

/**
 * What happened to a dialog, published by message-service to `messages.delivery` and consumed by
 * every websocket-gateway instance: every node sees every event and delivers to whatever sessions
 * it holds, which is what per-instance consumer groups buy.
 *
 * Two of the three cases are the outcome of a send; [Read] is not, and shares the topic anyway
 * because it is keyed by the same dialogId and must stay ordered against the messages it
 * acknowledges. See [KafkaTopics.MESSAGES_DELIVERY].
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "outcome")
@JsonSubTypes(
    JsonSubTypes.Type(value = MessageDeliveryEvent.Accepted::class, name = "ACCEPTED"),
    JsonSubTypes.Type(value = MessageDeliveryEvent.Rejected::class, name = "REJECTED"),
    JsonSubTypes.Type(value = MessageDeliveryEvent.Read::class, name = "READ")
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

    /**
     * [readerId]'s read cursor in [dialogId] advanced to [upToMessageId], stored at [lastReadAt].
     * The gateway pushes `message.read` to every session of [recipientIds] except
     * [readerSessionId] — the device that read already knows, the reader's *other* devices need it
     * to clear their badge, and the other participants need it to draw read ticks.
     *
     * Published only when the cursor actually moved. A stale or repeated command stores nothing and
     * announces nothing, so a client retrying a read cannot make a receipt fire twice.
     *
     * [recipientIds] is the dialog's membership, resolved server-side for the same reason
     * [Accepted.recipientIds] is: the gateway must never work out who is in a dialog.
     */
    data class Read(
        val dialogId: String,
        val readerId: String,
        val readerSessionId: String?,
        val upToMessageId: String,
        val lastReadAt: Instant,
        val recipientIds: List<String>
    ) : MessageDeliveryEvent
}
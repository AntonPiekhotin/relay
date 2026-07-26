package com.relay.common.event

import java.time.Instant

/**
 * Published by message-service once a message is persisted.
 *
 * [recipientIds] is what the gateway routes on. It is carried in the event on purpose: the
 * gateway is a relay and must never have to resolve chat membership itself, so producers own
 * the fan-out list.
 *
 * [clientMessageId] is echoed back so the sender's own client can reconcile the pushed message
 * with the send it issued, whether that send went over the socket or fell back to REST.
 */
data class MessageCreatedEvent(
    val id: String,
    val chatId: String,
    val senderId: String,
    val body: String,
    val sentAt: Instant,
    val recipientIds: List<String>,
    val clientMessageId: String? = null
)
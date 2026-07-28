package com.relay.message.dto.event

import com.relay.message.model.Message

/**
 * Domain event raised inside the send transaction by MessageService; the output adapter
 * (`output.event.MessageEventPublisher`) turns it into a Kafka `messages.delivery` event only
 * once the transaction commits. Lives here, not in `output.event`, so the service layer never
 * depends on an output adapter's package.
 */
data class MessagePersisted(
    val message: Message,
    val recipientIds: Set<String>,
    val senderSessionId: String?
)
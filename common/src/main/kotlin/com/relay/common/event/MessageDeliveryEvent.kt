package com.relay.common.event

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "outcome")
@JsonSubTypes(
    JsonSubTypes.Type(value = MessageDeliveryEvent.Accepted::class, name = "ACCEPTED"),
    JsonSubTypes.Type(value = MessageDeliveryEvent.Rejected::class, name = "REJECTED"),
    JsonSubTypes.Type(value = MessageDeliveryEvent.Read::class, name = "READ"),
    JsonSubTypes.Type(value = MessageDeliveryEvent.GroupChanged::class, name = "GROUP_CHANGED")
)
sealed interface MessageDeliveryEvent {

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

    data class Rejected(
        val clientMessageId: String,
        val senderId: String,
        val senderSessionId: String?,
        val code: String,
        val reason: String
    ) : MessageDeliveryEvent

    data class Read(
        val dialogId: String,
        val readerId: String,
        val readerSessionId: String?,
        val upToMessageId: String,
        val lastReadAt: Instant,
        val recipientIds: List<String>
    ) : MessageDeliveryEvent

    data class GroupChanged(
        val dialogId: String,
        val change: String,
        val actorId: String,
        val targetUserId: String? = null,
        val title: String? = null,
        val messageId: String? = null,
        val sentAt: Instant,
        val recipientIds: List<String>
    ) : MessageDeliveryEvent
}
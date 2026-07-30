package com.relay.common.event

import java.time.Instant

/**
 * A request to notify one user who has no live socket (ARCHITECTURE.md §14.2: offline →
 * `notifications` → notification-service → FCM). One event per recipient, keyed by
 * [recipientId], so notification-service can look up that user's device tokens directly.
 *
 * [payload] is untyped for the same reason as [NotificationCreatedEvent]: its shape varies by
 * [kind] (`MESSAGE_NEW` carries message fields; a future `MISSED_CALL` carries call fields),
 * and only notification-service interprets it.
 */
data class NotificationRequestedEvent(
    val recipientId: String,
    val kind: String,
    val payload: Map<String, Any?> = emptyMap(),
    val requestedAt: Instant
) {
    companion object {
        const val KIND_MESSAGE_NEW = "MESSAGE_NEW"

        /** The push request for a chat message the recipient was not connected to receive. */
        fun messageNew(recipientId: String, event: MessageDeliveryEvent.Accepted) =
            NotificationRequestedEvent(
                recipientId = recipientId,
                kind = KIND_MESSAGE_NEW,
                payload = mapOf(
                    "messageId" to event.messageId,
                    "dialogId" to event.dialogId,
                    "senderId" to event.senderId,
                    "text" to event.text,
                    "sentAt" to event.sentAt.toString()
                ),
                requestedAt = event.sentAt
            )
    }
}
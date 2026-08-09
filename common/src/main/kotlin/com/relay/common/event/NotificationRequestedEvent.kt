package com.relay.common.event

import java.time.Instant

/**
 * A request to notify one user who has no live socket: offline → `notifications` →
 * notification-service → FCM. One event per recipient, keyed by [recipientId], so
 * notification-service can look up that user's device tokens directly.
 *
 * [payload] is untyped for the same reason as [NotificationCreatedEvent]: its shape varies by
 * [kind] (`MESSAGE_NEW` carries message fields, `INCOMING_CALL` and `MISSED_CALL` carry call
 * fields), and only notification-service interprets it.
 */
data class NotificationRequestedEvent(
    val recipientId: String,
    val kind: String,
    val payload: Map<String, Any?> = emptyMap(),
    val requestedAt: Instant
) {
    companion object {
        const val KIND_MESSAGE_NEW = "MESSAGE_NEW"

        /**
         * A call is ringing and the callee has no live socket to ring on. Time-critical and
         * short-lived: it is worthless once the ring timeout has passed, which is why the payload
         * carries [KEY_RING_EXPIRES_AT] for the client to discard a late arrival.
         */
        const val KIND_INCOMING_CALL = "INCOMING_CALL"

        /** A call rang out unanswered. Sent after the fact, so it is an ordinary alert. */
        const val KIND_MISSED_CALL = "MISSED_CALL"

        const val KEY_CALL_ID = "callId"
        const val KEY_CALLER_ID = "callerId"
        const val KEY_MEDIA = "media"
        const val KEY_RING_EXPIRES_AT = "ringExpiresAt"

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

        /**
         * The push request that rings a callee with no live socket. [ringExpiresAt] is when the
         * server stops ringing and calls it missed — a push that arrives after it must not raise
         * a call UI for a call that no longer exists.
         */
        fun incomingCall(
            recipientId: String,
            callId: String,
            callerId: String,
            media: String,
            requestedAt: Instant,
            ringExpiresAt: Instant
        ) = NotificationRequestedEvent(
            recipientId = recipientId,
            kind = KIND_INCOMING_CALL,
            payload = mapOf(
                KEY_CALL_ID to callId,
                KEY_CALLER_ID to callerId,
                KEY_MEDIA to media,
                KEY_RING_EXPIRES_AT to ringExpiresAt.toString()
            ),
            requestedAt = requestedAt
        )

        /** The push request for a call that rang out unanswered. */
        fun missedCall(
            recipientId: String,
            callId: String,
            callerId: String,
            media: String,
            requestedAt: Instant
        ) = NotificationRequestedEvent(
            recipientId = recipientId,
            kind = KIND_MISSED_CALL,
            payload = mapOf(
                KEY_CALL_ID to callId,
                KEY_CALLER_ID to callerId,
                KEY_MEDIA to media
            ),
            requestedAt = requestedAt
        )
    }
}
package com.relay.common.event

import java.time.Instant

data class NotificationRequestedEvent(
    val recipientId: String,
    val kind: String,
    val payload: Map<String, Any?> = emptyMap(),
    val requestedAt: Instant
) {
    companion object {
        const val KIND_MESSAGE_NEW = "MESSAGE_NEW"
        const val KIND_INCOMING_CALL = "INCOMING_CALL"
        const val KIND_MISSED_CALL = "MISSED_CALL"
        const val KEY_CALL_ID = "callId"
        const val KEY_CALLER_ID = "callerId"
        const val KEY_MEDIA = "media"
        const val KEY_RING_EXPIRES_AT = "ringExpiresAt"
        const val KEY_CALL_KIND = "callKind"
        const val CALL_KIND_DIRECT = "direct"
        const val CALL_KIND_GROUP = "group"

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

        fun incomingCall(
            recipientId: String,
            callId: String,
            callerId: String,
            media: String,
            requestedAt: Instant,
            ringExpiresAt: Instant,
            callKind: String = CALL_KIND_DIRECT
        ) = NotificationRequestedEvent(
            recipientId = recipientId,
            kind = KIND_INCOMING_CALL,
            payload = mapOf(
                KEY_CALL_ID to callId,
                KEY_CALLER_ID to callerId,
                KEY_MEDIA to media,
                KEY_RING_EXPIRES_AT to ringExpiresAt.toString(),
                KEY_CALL_KIND to callKind
            ),
            requestedAt = requestedAt
        )

        fun missedCall(
            recipientId: String,
            callId: String,
            callerId: String,
            media: String,
            requestedAt: Instant,
            callKind: String = CALL_KIND_DIRECT
        ) = NotificationRequestedEvent(
            recipientId = recipientId,
            kind = KIND_MISSED_CALL,
            payload = mapOf(
                KEY_CALL_ID to callId,
                KEY_CALLER_ID to callerId,
                KEY_MEDIA to media,
                KEY_CALL_KIND to callKind
            ),
            requestedAt = requestedAt
        )
    }
}
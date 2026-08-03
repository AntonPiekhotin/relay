package com.relay.common.event

/**
 * Topic names, separated by traffic class and keyed by dialogId so a conversation's messages
 * stay ordered within one partition. See `docs/KAFKA.md`.
 */
object KafkaTopics {

    /**
     * Partition count for every topic here, and therefore the ceiling on listener concurrency —
     * a consumer group can never have more members processing than there are partitions.
     *
     * It lives in one place because the count has to agree across services: topics are created
     * by whichever service produces to them, and a mismatch would mean whoever starts first
     * silently decides the layout. Changing it after messages exist re-shuffles which partition
     * a dialogId hashes to, which breaks per-conversation ordering across the change.
     */
    const val PARTITIONS = 3

    /** Client sends awaiting persistence. websocket-gateway → message-service. */
    const val MESSAGES_INCOMING = "messages.incoming"

    /** Fan-out events after (attempted) persistence. message-service → websocket-gateway. */
    const val MESSAGES_DELIVERY = "messages.delivery"

    /**
     * Push notification requests for recipients with no live socket. websocket-gateway
     * (interim; later any service) → notification-service. Keyed by recipientId.
     */
    const val NOTIFICATIONS = "notifications"

    /**
     * In-app notifications for connected users, pushed as `notification.new` frames.
     * notification-service → websocket-gateway. The socket-XOR-push decision is what separates
     * this topic from [NOTIFICATIONS].
     */
    const val NOTIFICATIONS_DELIVERY = "notifications.delivery"

    /**
     * Call signaling relay. NOTE: the architecture routes signaling around Kafka entirely, on
     * the grounds that queue latency is invisible for chat and fatal for call setup; whether
     * this topic survives is to be decided when the call service is implemented.
     */
    const val CALL_SIGNAL = "call.signal"
}

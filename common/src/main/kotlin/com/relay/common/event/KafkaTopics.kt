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

    /**
     * Read cursors moving forward. websocket-gateway → message-service, keyed by dialogId so a
     * conversation's reads stay ordered against each other.
     *
     * Its own topic rather than a second shape on [MESSAGES_INCOMING]: a read is a different
     * traffic class from a send, and a backlog of reads must not add lag to message persistence
     * (`docs/KAFKA.md` §2.1). The receipt going back out does *not* get its own topic — see
     * [MESSAGES_DELIVERY].
     */
    const val MESSAGES_READ = "messages.read"

    /**
     * Fan-out events after (attempted) persistence, and read receipts. message-service →
     * websocket-gateway.
     *
     * Read receipts share this topic on purpose. Keyed by dialogId like every other event here,
     * they land in the same partition as the messages they acknowledge, so a receipt can never
     * overtake the `message.new` it refers to. A separate topic would be two ordering domains for
     * one conversation, and a client could be told a message was read before being told it exists.
     */
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
     * Call signaling relay. call-service → websocket-gateway, keyed by callId so one call's
     * signals stay ordered within a partition.
     *
     * Only the *outbound* leg runs here. A client's signal reaches call-service directly over
     * HTTP, because that half is a request the gateway needs an answer to; the relay back out
     * stays on Kafka because the gateway's broadcast group already delivers to whichever node
     * holds the target socket, which a load-balanced HTTP push cannot do until the shared session
     * registry exists. See `docs/ARCHITECTURE.md` §7 and decision 21.
     */
    const val CALL_SIGNAL = "call.signal"
}

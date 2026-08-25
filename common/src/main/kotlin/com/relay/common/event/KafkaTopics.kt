package com.relay.common.event

object KafkaTopics {

    const val PARTITIONS = 3

    const val MESSAGES_INCOMING = "messages.incoming"

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
     * Presence transitions. websocket-gateway → websocket-gateway, keyed by the subject's userId so
     * one user's transitions stay ordered — an `offline` overtaking a later `online` would leave
     * subscribers showing a stale dot until the next transition.
     *
     * Producer and consumer are the same service, which is unusual here and deliberate: the fan-out
     * target is "whichever node holds a subscriber", exactly like [CALL_SIGNAL]. Each node consumes
     * every event and delivers to the subscriptions it holds locally.
     */
    const val PRESENCE_UPDATE = "presence.update"

    /**
     * Typing indicators. websocket-gateway → websocket-gateway, keyed by dialogId like every other
     * dialog-scoped topic.
     *
     * Its own topic rather than a second shape on [PRESENCE_UPDATE]: the two are keyed differently —
     * one by user, one by dialog — and a topic cannot be keyed two ways. Typing is also by far the
     * higher-volume of the two, and a burst of indicators must not add lag to presence transitions
     * (§2.1).
     */
    const val TYPING_START = "typing.start"

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

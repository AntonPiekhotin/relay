package com.relay.common.event

/**
 * Topic names per ARCHITECTURE.md §13.2 — separated by traffic class, keyed by dialogId so a
 * conversation's messages stay ordered within one partition.
 */
object KafkaTopics {

    /** Client sends awaiting persistence. websocket-gateway → message-service. */
    const val MESSAGES_INCOMING = "messages.incoming"

    /** Fan-out events after (attempted) persistence. message-service → websocket-gateway. */
    const val MESSAGES_DELIVERY = "messages.delivery"

    /** Push notification requests. Producers various → notification-service / gateway. */
    const val NOTIFICATIONS = "notifications"

    /**
     * Call signaling relay. NOTE: ARCHITECTURE.md §17.3 routes signaling around Kafka entirely;
     * whether this topic survives is to be decided when the call service is implemented.
     */
    const val CALL_SIGNAL = "call.signal"
}
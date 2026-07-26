package com.relay.common.event

/** Topics the websocket-gateway consumes to push frames to connected clients. */
object KafkaTopics {

    const val MESSAGE_CREATED = "message.created"

    const val NOTIFICATION_CREATED = "notification.created"

    const val CALL_SIGNAL = "call.signal"
}
package com.relay.websocket.consumer

import com.relay.common.event.CallSignalEvent
import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageCreatedEvent
import com.relay.common.event.NotificationCreatedEvent
import com.relay.websocket.fanout.FrameDispatcher
import com.relay.websocket.protocol.OutboundFrame
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Every topic the gateway consumes does the same two things — decode, then fan out to the
 * recipients the producer named — so they live together rather than in a class each.
 *
 * These run on Kafka listener threads, not the Netty event loop. Handing frames to a session's
 * sink is the only work done here, so nothing blocks.
 */
@Component
class KafkaFanoutListener(
    private val dispatcher: FrameDispatcher,
    private val codec: EventCodec
) {

    @KafkaListener(topics = [KafkaTopics.MESSAGE_CREATED])
    fun onMessageCreated(raw: String) {
        val event = codec.decode(KafkaTopics.MESSAGE_CREATED, raw, MessageCreatedEvent::class.java)
            ?: return
        dispatcher.dispatch(
            event.recipientIds,
            OutboundFrame.MessageNew(
                id = event.id,
                chatId = event.chatId,
                senderId = event.senderId,
                body = event.body,
                sentAt = event.sentAt,
                clientMessageId = event.clientMessageId
            )
        )
    }

    @KafkaListener(topics = [KafkaTopics.NOTIFICATION_CREATED])
    fun onNotificationCreated(raw: String) {
        val event = codec.decode(KafkaTopics.NOTIFICATION_CREATED, raw, NotificationCreatedEvent::class.java)
            ?: return
        dispatcher.dispatch(
            event.recipientIds,
            OutboundFrame.Notification(
                id = event.id,
                kind = event.kind,
                payload = event.payload,
                createdAt = event.createdAt
            )
        )
    }

    @KafkaListener(topics = [KafkaTopics.CALL_SIGNAL])
    fun onCallSignal(raw: String) {
        val event = codec.decode(KafkaTopics.CALL_SIGNAL, raw, CallSignalEvent::class.java)
            ?: return
        dispatcher.dispatch(
            event.recipientIds,
            OutboundFrame.CallSignal(
                callId = event.callId,
                fromUserId = event.fromUserId,
                signal = event.signal
            )
        )
    }
}
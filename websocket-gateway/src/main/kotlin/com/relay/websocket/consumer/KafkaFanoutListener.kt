package com.relay.websocket.consumer

import com.relay.common.event.CallSignalEvent
import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageDeliveryEvent
import com.relay.common.event.NotificationCreatedEvent
import com.relay.websocket.fanout.FrameDispatcher
import com.relay.websocket.protocol.OutboundFrame
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Turns broker events into frames on live sockets. Runs on Kafka listener threads, not the
 * Netty event loop — handing frames to a session's sink is the only work done here.
 *
 * Consumer groups are per-instance (see application.yaml): every gateway node sees every event
 * and delivers to whatever sessions it holds. See the follow-up recorded in ARCHITECTURE.md §23
 * before changing the group strategy.
 */
@Component
class KafkaFanoutListener(
    private val dispatcher: FrameDispatcher,
    private val codec: EventCodec
) {

    /**
     * The outcome of a send (ARCHITECTURE.md §20.1 steps 7–10): ack (or error) to the exact
     * session that sent; `message.new` to every other session of every recipient. A duplicate
     * outcome acks the sender but fans out nothing — the original already did.
     */
    @KafkaListener(topics = [KafkaTopics.MESSAGES_DELIVERY])
    fun onDeliveryEvent(raw: String) {
        val event = codec.decode(KafkaTopics.MESSAGES_DELIVERY, raw, MessageDeliveryEvent::class.java)
            ?: return
        when (event) {
            is MessageDeliveryEvent.Accepted -> {
                event.senderSessionId?.let { sessionId ->
                    dispatcher.deliverToSession(
                        event.senderId,
                        sessionId,
                        OutboundFrame.Ack(
                            clientMsgId = event.clientMessageId,
                            messageId = event.messageId,
                            createdAt = event.sentAt
                        )
                    )
                }
                if (!event.duplicate) {
                    dispatcher.deliverToUsersExcept(
                        event.recipientIds,
                        event.senderSessionId,
                        OutboundFrame.MessageNew(
                            messageId = event.messageId,
                            dialogId = event.dialogId,
                            senderId = event.senderId,
                            text = event.text,
                            createdAt = event.sentAt
                        )
                    )
                }
            }
            is MessageDeliveryEvent.Rejected -> {
                event.senderSessionId?.let { sessionId ->
                    dispatcher.deliverToSession(
                        event.senderId,
                        sessionId,
                        OutboundFrame.Error(event.code, event.reason, event.clientMessageId)
                    )
                }
            }
        }
    }

    @KafkaListener(topics = [KafkaTopics.NOTIFICATIONS])
    fun onNotification(raw: String) {
        val event = codec.decode(KafkaTopics.NOTIFICATIONS, raw, NotificationCreatedEvent::class.java)
            ?: return
        dispatcher.deliverToUsers(
            event.recipientIds,
            OutboundFrame.Notification(
                notificationId = event.id,
                kind = event.kind,
                data = event.payload,
                createdAt = event.createdAt
            )
        )
    }

    @KafkaListener(topics = [KafkaTopics.CALL_SIGNAL])
    fun onCallSignal(raw: String) {
        val event = codec.decode(KafkaTopics.CALL_SIGNAL, raw, CallSignalEvent::class.java)
            ?: return
        dispatcher.deliverToUsers(
            event.recipientIds,
            OutboundFrame.CallSignal(
                callId = event.callId,
                fromUserId = event.fromUserId,
                signal = event.signal
            )
        )
    }
}
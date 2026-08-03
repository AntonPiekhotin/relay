package com.relay.websocket.input.event

import com.relay.common.event.CallSignalEvent
import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageDeliveryEvent
import com.relay.common.event.NotificationCreatedEvent
import com.relay.common.event.NotificationRequestedEvent
import com.relay.websocket.output.event.NotificationEventProducer
import com.relay.websocket.output.socket.FrameDispatcher
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.SessionRegistry
import com.relay.websocket.util.EventCodec
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Turns broker events into frames on live sockets. Runs on Kafka listener threads — handing a
 * frame to a session's outbound queue is the only work done here.
 *
 * Consumer groups are per-instance (see application.yaml): every gateway node sees every event
 * and delivers to whatever sessions it holds. A shared group would split partitions across
 * instances and silently drop events at whichever one does not hold the target session, so do
 * not change the group strategy without reading `docs/KAFKA.md` first.
 */
@Component
class KafkaEventConsumer(
    private val dispatcher: FrameDispatcher,
    private val codec: EventCodec,
    private val registry: SessionRegistry,
    private val notificationEventProducer: NotificationEventProducer
) {

    /**
     * The outcome of a send: ack (or error) to the exact session that sent; `message.new` to
     * every other session of every recipient. A duplicate outcome acks the sender but fans out
     * nothing — the original already did.
     */
    @KafkaListener(
        topics = [KafkaTopics.MESSAGES_DELIVERY],
        concurrency = "#{T(com.relay.common.event.KafkaTopics).PARTITIONS}"
    )
    fun onDeliveryEvent(raw: String) {
        val event = codec.decode(KafkaTopics.MESSAGES_DELIVERY, raw, MessageDeliveryEvent::class.java)
            ?: return
        when (event) {
            is MessageDeliveryEvent.Accepted -> {
                sendAckToMessageSender(event)
                if (!event.duplicate) {
                    sendMessageToRecipients(event)
                    requestNotificationsForOfflineRecipients(event)
                }
            }
            is MessageDeliveryEvent.Rejected -> handleRejectedMessage(event)

        }
    }

    private fun sendAckToMessageSender(event: MessageDeliveryEvent.Accepted) {
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
    }

    private fun sendMessageToRecipients(event: MessageDeliveryEvent.Accepted) {
        dispatcher.deliverToUsersExcept(
            event.recipientIds,
            setOf(event.senderSessionId),
            OutboundFrame.MessageNew(
                messageId = event.messageId,
                dialogId = event.dialogId,
                senderId = event.senderId,
                text = event.text,
                createdAt = event.sentAt
            )
        )
    }

    /**
     * Socket XOR push: a recipient with no live session gets a push
     * notification request instead of a frame. The sender is never notified about their own
     * message, whatever their connection state.
     *
     * The online check is this node's in-memory registry — the global truth only while the
     * gateway runs as a single instance. On multiple nodes each instance would wrongly declare
     * users on *other* nodes offline; this decision moves into the shared session registry when
     * delivery routing becomes per-node.
     */
    private fun requestNotificationsForOfflineRecipients(event: MessageDeliveryEvent.Accepted) {
        event.recipientIds
            .distinct()
            .filter { it != event.senderId && !registry.isOnline(it) }
            .forEach { recipientId ->
                notificationEventProducer.publish(NotificationRequestedEvent.messageNew(recipientId, event))
            }
    }

    private fun handleRejectedMessage(event: MessageDeliveryEvent.Rejected) {
        event.senderSessionId?.let { sessionId ->
            dispatcher.deliverToSession(
                event.senderId,
                sessionId,
                OutboundFrame.Error(event.code, event.reason, event.clientMessageId)
            )
        }
    }

    /**
     * In-app notifications for users notification-service found connected — the other half of
     * the XOR. The requests topic ([KafkaTopics.NOTIFICATIONS]) flows the opposite way and is
     * deliberately not consumed here.
     */
    @KafkaListener(
        topics = [KafkaTopics.NOTIFICATIONS_DELIVERY],
        concurrency = "#{T(com.relay.common.event.KafkaTopics).PARTITIONS}"
    )
    fun onNotification(raw: String) {
        val event = codec.decode(KafkaTopics.NOTIFICATIONS_DELIVERY, raw, NotificationCreatedEvent::class.java)
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

    @KafkaListener(
        topics = [KafkaTopics.CALL_SIGNAL],
        concurrency = "#{T(com.relay.common.event.KafkaTopics).PARTITIONS}"
    )
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
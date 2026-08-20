package com.relay.websocket.input.event

import com.relay.common.event.CallSignalEvent
import com.relay.common.event.CallSignalKeys
import com.relay.common.event.CallSignalVerbs
import com.relay.common.event.GroupChangeTypes
import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageDeliveryEvent
import com.relay.common.event.NotificationCreatedEvent
import com.relay.common.event.NotificationRequestedEvent
import com.relay.common.event.PresenceEvent
import com.relay.common.event.TypingEvent
import com.relay.websocket.output.event.NotificationEventProducer
import com.relay.websocket.output.http.DialogMembershipResolver
import com.relay.websocket.output.socket.FrameDispatcher
import com.relay.websocket.presence.PresenceService
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.SessionRegistry
import com.relay.websocket.util.EventCodec
import java.time.Instant
import org.slf4j.LoggerFactory
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
    private val notificationEventProducer: NotificationEventProducer,
    private val presenceService: PresenceService,
    private val membershipResolver: DialogMembershipResolver
) {

    private val logger = LoggerFactory.getLogger(javaClass)

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
            is MessageDeliveryEvent.Read -> sendReadReceipt(event)
            is MessageDeliveryEvent.GroupChanged -> onGroupChanged(event)
        }
    }

    /**
     * A group changed shape. Order matters inside this handler:
     *
     * 1. **Invalidate the membership cache first.** The cached list is also the authorization
     *    answer for presence and typing, so it must be gone before anything here — or any frame
     *    arriving after this one — re-resolves.
     * 2. Fix up this node's presence subscriptions (tear down the removed member's watch, refresh
     *    the others).
     * 3. Fan the frame out — to every recipient *including* the actor's own devices: the system
     *    message has to render in the actor's chat too, and clients dedupe on `message_id` exactly
     *    as they do for `message.new`.
     *
     * No offline push: a member who missed this discovers it from the dialog list and history on
     * reconnect, the same recovery every dropped frame relies on (`docs/PROTOCOL.md` §7).
     */
    private fun onGroupChanged(event: MessageDeliveryEvent.GroupChanged) {
        membershipResolver.invalidate(event.dialogId)
        presenceService.onGroupChanged(event)

        if (event.change == GroupChangeTypes.GROUP_DELETED) {
            dispatcher.deliverToUsers(
                event.recipientIds,
                OutboundFrame.DialogDeleted(dialogId = event.dialogId, actorId = event.actorId)
            )
            return
        }
        val kind = WIRE_KIND_BY_CHANGE[event.change]
        val messageId = event.messageId
        if (kind == null || messageId == null) {
            // A change this gateway does not know is not an error — the cache invalidation and
            // presence fix-up above still ran, which is the part that cannot wait for a deploy.
            logger.debug("No frame for group change {} on dialog {}", event.change, event.dialogId)
            return
        }
        dispatcher.deliverToUsers(
            event.recipientIds,
            OutboundFrame.MessageSystem(
                messageId = messageId,
                dialogId = event.dialogId,
                actorId = event.actorId,
                kind = kind,
                targetUserId = event.targetUserId,
                title = event.title,
                createdAt = event.sentAt
            )
        )
    }

    private fun sendReadReceipt(event: MessageDeliveryEvent.Read) {
        dispatcher.deliverToUsersExcept(
            event.recipientIds,
            setOf(event.readerSessionId),
            OutboundFrame.MessageRead(
                dialogId = event.dialogId,
                userId = event.readerId,
                upToMessageId = event.upToMessageId,
                readAt = event.lastReadAt
            )
        )
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

    private companion object {
        /**
         * Event vocabulary → wire vocabulary, translated rather than lowercased in place, for the
         * same reason [PresenceService.deliver] maps statuses: the internal spelling must be able
         * to change without silently changing the client contract. `GROUP_DELETED` is absent — it
         * becomes a `dialog.deleted` frame, not a system message.
         */
        val WIRE_KIND_BY_CHANGE: Map<String, String> = mapOf(
            GroupChangeTypes.GROUP_CREATED to "group_created",
            GroupChangeTypes.MEMBER_ADDED to "member_added",
            GroupChangeTypes.MEMBER_REMOVED to "member_removed",
            GroupChangeTypes.MEMBER_LEFT to "member_left",
            GroupChangeTypes.GROUP_RENAMED to "group_renamed"
        )
    }

    /**
     * Presence transitions, published by whichever node observed the socket open or close.
     *
     * **This is the only pair of topics the gateway produces to and consumes from**, and the round
     * trip is the point: the node that saw Bob's phone connect is not necessarily the node holding
     * Alice, who is watching him. Every node sees every transition and serves the subscriptions it
     * holds, exactly like [onCallSignal].
     *
     * A user with no subscribers on this node is the common case and costs nothing — the event is
     * decoded, finds an empty subscriber set, and is dropped.
     */
    @KafkaListener(
        topics = [KafkaTopics.PRESENCE_UPDATE],
        concurrency = "#{T(com.relay.common.event.KafkaTopics).PARTITIONS}"
    )
    fun onPresenceUpdate(raw: String) {
        val event = codec.decode(KafkaTopics.PRESENCE_UPDATE, raw, PresenceEvent::class.java) ?: return
        presenceService.deliver(event)
    }

    /**
     * Typing indicators. The recipient list was resolved by the publishing node and travels on the
     * event, so nothing here asks message-service anything — a consuming node holding one socket of a
     * conversation must not have to look up who else is in it.
     */
    @KafkaListener(
        topics = [KafkaTopics.TYPING_START],
        concurrency = "#{T(com.relay.common.event.KafkaTopics).PARTITIONS}"
    )
    fun onTypingStart(raw: String) {
        val event = codec.decode(KafkaTopics.TYPING_START, raw, TypingEvent::class.java) ?: return
        presenceService.deliver(event)
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

    /**
     * Relays a call signal verbatim. The gateway reads nothing inside [CallSignalEvent.signal]
     * except the verb, and only to answer one question: does an unreachable callee need a push?
     */
    @KafkaListener(
        topics = [KafkaTopics.CALL_SIGNAL],
        concurrency = "#{T(com.relay.common.event.KafkaTopics).PARTITIONS}"
    )
    fun onCallSignal(raw: String) {
        val event = codec.decode(KafkaTopics.CALL_SIGNAL, raw, CallSignalEvent::class.java)
            ?: return
        val frame = OutboundFrame.CallSignal(
            callId = event.callId,
            fromUserId = event.fromUserId,
            signal = event.signal
        )
        if (event.excludeSessionIds.isEmpty()) {
            dispatcher.deliverToUsers(event.recipientIds, frame)
        } else {
            // A user's other devices are told to stop ringing; the one that answered is not.
            dispatcher.deliverToUsersExcept(event.recipientIds, event.excludeSessionIds.toSet(), frame)
        }
        requestPushForUnreachableCallees(event)
    }

    /**
     * Socket XOR push, for calls. A callee with no live session cannot be rung with a frame, so the
     * invite becomes a push request instead — the same decision, and the same in-memory-registry
     * caveat, as [requestNotificationsForOfflineRecipients].
     *
     * Only invites — direct or group. Every other verb concerns a call the client is already
     * tracking, and a push for one would be noise. Both invite verbs carry `media` and
     * `ring_expires_at` in the same keys, which is what lets one extraction serve both.
     */
    private fun requestPushForUnreachableCallees(event: CallSignalEvent) {
        val verb = event.signal[CallSignalKeys.VERB]
        if (verb != CallSignalVerbs.INVITE && verb != CallSignalVerbs.GROUP_INVITE) return
        val media = event.signal[CallSignalKeys.MEDIA] as? String ?: return
        val ringExpiresAt = (event.signal[CallSignalKeys.RING_EXPIRES_AT] as? String)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return
        val callKind = event.signal[CallSignalKeys.KIND] as? String
            ?: NotificationRequestedEvent.CALL_KIND_DIRECT

        event.recipientIds
            .distinct()
            .filter { it != event.fromUserId && !registry.isOnline(it) }
            .forEach { recipientId ->
                notificationEventProducer.publish(
                    NotificationRequestedEvent.incomingCall(
                        recipientId = recipientId,
                        callId = event.callId,
                        callerId = event.fromUserId,
                        media = media,
                        requestedAt = Instant.now(),
                        ringExpiresAt = ringExpiresAt,
                        callKind = callKind
                    )
                )
            }
    }
}
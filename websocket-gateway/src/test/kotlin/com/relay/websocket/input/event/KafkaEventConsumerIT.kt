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
import com.relay.common.event.PresenceStatuses
import com.relay.common.event.TypingEvent
import com.relay.common.model.UserPrincipal
import com.relay.websocket.output.http.DialogMembershipClient
import com.relay.websocket.output.http.DialogMembershipResolver
import com.relay.websocket.presence.PresenceSubscriptions
import com.relay.websocket.presence.StubDialogMembershipClient
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.RelaySession
import com.relay.websocket.session.SessionRegistry
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import tools.jackson.databind.json.JsonMapper

/**
 * Exercises the real listeners against an in-JVM broker: publish a delivery event, assert the
 * right frames land on the right sessions — ack to the sending device only, message.new to
 * everyone else, error on rejection.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"
    ]
)
// Partitioned like production, not with a convenient 1: the app's own NewTopic beans would
// raise a 1-partition topic to PARTITIONS anyway, and the listeners request that much
// concurrency, so an under-partitioned broker only hides what the real one will do.
@EmbeddedKafka(
    partitions = KafkaTopics.PARTITIONS,
    topics = [
        KafkaTopics.MESSAGES_DELIVERY,
        KafkaTopics.NOTIFICATIONS,
        KafkaTopics.NOTIFICATIONS_DELIVERY,
        KafkaTopics.CALL_SIGNAL,
        KafkaTopics.PRESENCE_UPDATE,
        KafkaTopics.TYPING_START
    ]
)
class KafkaEventConsumerIT {

    /**
     * The group-change path re-resolves membership after invalidating the cache; over HTTP that
     * would need a live message-service, so the client port is stubbed. Every other test here
     * never touches it.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class StubMembershipConfig {
        @Bean
        @Primary
        fun stubMembershipClient(): DialogMembershipClient = StubDialogMembershipClient()
    }

    @Autowired private lateinit var registry: SessionRegistry
    @Autowired private lateinit var subscriptions: PresenceSubscriptions
    @Autowired private lateinit var membershipResolver: DialogMembershipResolver
    @Autowired private lateinit var membershipClient: DialogMembershipClient
    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired private lateinit var jsonMapper: JsonMapper
    @Autowired private lateinit var endpoints: KafkaListenerEndpointRegistry
    @Autowired private lateinit var broker: EmbeddedKafkaBroker

    private val timeout = Duration.ofSeconds(30)
    private var counter = 0

    private lateinit var notificationRequests: Consumer<String, String>

    @BeforeTest
    fun waitForPartitionAssignment() {
        // Each container is alone in its group, so it owns every partition of its topic.
        endpoints.listenerContainers.forEach {
            ContainerTestUtils.waitForAssignment(it, KafkaTopics.PARTITIONS)
        }
    }

    @BeforeTest
    fun subscribeToNotificationRequests() {
        val props = KafkaTestUtils.consumerProps(broker, "notif-it-${UUID.randomUUID()}", true)
            .toMutableMap()
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        notificationRequests = DefaultKafkaConsumerFactory(props, StringDeserializer(), StringDeserializer())
            .createConsumer()
        notificationRequests.subscribe(listOf(KafkaTopics.NOTIFICATIONS))
        // Wait for assignment, then skip whatever earlier tests produced on the shared topic —
        // each test must assert only its own requests.
        val deadline = System.currentTimeMillis() + 10_000
        while (notificationRequests.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
            notificationRequests.poll(Duration.ofMillis(100))
        }
        notificationRequests.seekToEnd(notificationRequests.assignment())
        // seekToEnd is lazy — it resolves on the next poll/position call. Forcing it here,
        // BEFORE the test publishes, so records produced during the test are not skipped.
        notificationRequests.assignment().forEach { notificationRequests.position(it) }
    }

    @AfterTest
    fun closeConsumer() {
        notificationRequests.close()
    }

    /** Keyed records from `notifications`, i.e. what step 2's notification-service will read. */
    private fun requestedNotifications(): List<Pair<String?, NotificationRequestedEvent>> =
        KafkaTestUtils.getRecords(notificationRequests, Duration.ofSeconds(10))
            .records(KafkaTopics.NOTIFICATIONS)
            .map { it.key() to jsonMapper.readValue(it.value(), NotificationRequestedEvent::class.java) }

    private fun assertNoRequestedNotifications() {
        val records = KafkaTestUtils.getRecords(notificationRequests, Duration.ofSeconds(2), 1)
        assertEquals(0, records.count(), "no push may be requested here")
    }

    private fun sessionFor(userId: String): RelaySession =
        RelaySession("s-$userId-${counter++}", UserPrincipal(userId, null, emptySet()), 32)
            .also(registry::register)

    /**
     * Stands in for the writer thread. Bounded because the frame is produced asynchronously by a
     * Kafka listener — an unbounded take would hang the suite instead of failing it.
     */
    private fun RelaySession.nextFrame(): OutboundFrame {
        val next = awaitOutbound(timeout) ?: fail("no frame reached session $sessionId within $timeout")
        return assertIs<RelaySession.Outbound.Frame>(next).frame
    }

    private fun publish(topic: String, event: Any) {
        kafkaTemplate.send(topic, jsonMapper.writeValueAsString(event)).get()
    }

    /**
     * Short on purpose: proving a frame did *not* arrive means waiting, and the listener has already
     * been given time to deliver by whatever assertion came before this one.
     */
    private fun assertNoFrames(session: RelaySession) {
        assertEquals(
            null,
            session.awaitOutbound(Duration.ofSeconds(2)),
            "session ${session.sessionId} should have received nothing"
        )
    }

    private fun accepted(
        sender: String,
        senderSessionId: String?,
        recipients: List<String>,
        clientMessageId: String = "c-1",
        duplicate: Boolean = false
    ) = MessageDeliveryEvent.Accepted(
        messageId = "m-$clientMessageId",
        dialogId = "d-1",
        senderId = sender,
        senderSessionId = senderSessionId,
        text = "hello",
        sentAt = Instant.parse("2026-07-26T10:00:00Z"),
        recipientIds = recipients,
        clientMessageId = clientMessageId,
        duplicate = duplicate
    )

    @Test
    fun `acks the sending device and pushes message-new to the recipient`() {
        val alice = sessionFor("alice-ack")
        val bob = sessionFor("bob-ack")

        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            accepted("alice-ack", alice.sessionId, listOf("alice-ack", "bob-ack"))
        )

        alice.nextFrame().let { frame ->
            val ack = assertIs<OutboundFrame.Ack>(frame)
            assertEquals("c-1", ack.clientMsgId)
            assertEquals("m-c-1", ack.messageId)
        }

        bob.nextFrame().let { frame ->
            val message = assertIs<OutboundFrame.MessageNew>(frame)
            assertEquals("hello", message.text)
            assertEquals("alice-ack", message.senderId)
        }
    }

    @Test
    fun `the sender's other device gets message-new, the sending device only the ack`() {
        val sendingDevice = sessionFor("alice-multi")
        val otherDevice = sessionFor("alice-multi")

        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            accepted("alice-multi", sendingDevice.sessionId, listOf("alice-multi"))
        )

        otherDevice.nextFrame().let { assertIs<OutboundFrame.MessageNew>(it)}

        sendingDevice.nextFrame().let { assertIs<OutboundFrame.Ack>(it)}
    }

    @Test
    fun `a duplicate outcome acks the sender but fans out nothing`() {
        val alice = sessionFor("alice-dup")
        val bob = sessionFor("bob-dup")

        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            accepted("alice-dup", alice.sessionId, emptyList(), clientMessageId = "c-dup", duplicate = true)
        )

        alice.nextFrame().let { assertEquals("c-dup", assertIs<OutboundFrame.Ack>(it).clientMsgId)}

        bob.complete()
        assertEquals(RelaySession.Outbound.Completed, bob.awaitOutbound())
    }

    @Test
    fun `a rejection reaches the sending device as an error with the service's code`() {
        val mallory = sessionFor("mallory-rej")

        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            MessageDeliveryEvent.Rejected(
                clientMessageId = "c-403",
                senderId = "mallory-rej",
                senderSessionId = mallory.sessionId,
                code = "NOT_A_PARTICIPANT",
                reason = "Sender is not a participant of dialog d-1"
            )
        )

        mallory.nextFrame().let { frame ->
            val error = assertIs<OutboundFrame.Error>(frame)
            assertEquals("NOT_A_PARTICIPANT", error.code)
            assertEquals("c-403", error.refId)
        }
    }

    @Test
    fun `requests a push for the offline recipient and only for them`() {
        val alice = sessionFor("alice-xor")
        // bob-offline-xor has no session; carol is connected.
        val carol = sessionFor("carol-xor")

        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            accepted("alice-xor", alice.sessionId, listOf("alice-xor", "bob-offline-xor", "carol-xor"))
        )

        // Carol got the frame (socket half of the XOR)...
        carol.nextFrame().let { assertIs<OutboundFrame.MessageNew>(it)}

        // ...and exactly one push request exists, for bob, keyed by his id (push half).
        val requests = requestedNotifications()
        assertEquals(1, requests.size, "online recipients and the sender must not be pushed")
        val (key, request) = requests.single()
        assertEquals("bob-offline-xor", key, "keyed by recipient so step 2 reads per-user in order")
        assertEquals("bob-offline-xor", request.recipientId)
        assertEquals(NotificationRequestedEvent.KIND_MESSAGE_NEW, request.kind)
        assertEquals("hello", request.payload["text"])
        assertEquals("alice-xor", request.payload["senderId"])
    }

    @Test
    fun `an offline sender is never notified about their own message`() {
        // REST-fallback shape: sender has no socket at all, recipient offline too.
        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            accepted("alice-rest", null, listOf("alice-rest", "bob-rest-offline"))
        )

        val requests = requestedNotifications()
        assertEquals(1, requests.size)
        assertEquals("bob-rest-offline", requests.single().second.recipientId)
    }

    @Test
    fun `a duplicate outcome requests no notifications`() {
        val alice = sessionFor("alice-dup-notif")

        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            accepted(
                "alice-dup-notif", alice.sessionId, listOf("alice-dup-notif", "bob-dup-offline"),
                clientMessageId = "c-dup-n", duplicate = true
            )
        )

        // The ack still arrives...
        alice.nextFrame().let { assertIs<OutboundFrame.Ack>(it)}
        // ...but a retry of an already-delivered message must not buzz anyone's phone again.
        assertNoRequestedNotifications()
    }

    @Test
    fun `pushes a notification with its untyped payload intact`() {
        val bob = sessionFor("bob-notification")

        publish(
            KafkaTopics.NOTIFICATIONS_DELIVERY,
            NotificationCreatedEvent(
                id = "n-1",
                kind = "FRIEND_REQUEST",
                payload = mapOf("fromUserId" to "alice"),
                createdAt = Instant.parse("2026-07-26T10:00:00Z"),
                recipientIds = listOf("bob-notification")
            )
        )

        bob.nextFrame().let { frame ->
            val notification = assertIs<OutboundFrame.Notification>(frame)
            assertEquals("FRIEND_REQUEST", notification.kind)
            assertEquals("alice", notification.data["fromUserId"])
        }
    }

    @Test
    fun `relays a call signal verbatim`() {
        val bob = sessionFor("bob-call")

        publish(
            KafkaTopics.CALL_SIGNAL,
            CallSignalEvent(
                callId = "call-1",
                fromUserId = "alice",
                signal = mapOf("kind" to "offer", "sdp" to "v=0..."),
                recipientIds = listOf("bob-call")
            )
        )

        bob.nextFrame().let { frame ->
            val signal = assertIs<OutboundFrame.CallSignal>(frame)
            assertEquals("call-1", signal.callId)
            assertEquals("offer", signal.signal["kind"])
        }
    }

    @Test
    fun `an excluded session is skipped while the user's other devices are reached`() {
        val answering = sessionFor("bob-multi-call")
        val other = sessionFor("bob-multi-call")

        publish(
            KafkaTopics.CALL_SIGNAL,
            CallSignalEvent(
                callId = "call-cancel",
                fromUserId = "bob-multi-call",
                signal = mapOf(CallSignalKeys.VERB to CallSignalVerbs.CANCEL),
                recipientIds = listOf("bob-multi-call"),
                excludeSessionIds = listOf(answering.sessionId)
            )
        )

        assertEquals(
            CallSignalVerbs.CANCEL,
            assertIs<OutboundFrame.CallSignal>(other.nextFrame()).signal[CallSignalKeys.VERB]
        )

        // The terminal marker arriving first proves nothing was queued to the answering device.
        answering.complete()
        assertEquals(
            RelaySession.Outbound.Completed,
            answering.awaitOutbound(),
            "the device that just answered must not be told to cancel"
        )
    }

    @Test
    fun `an invite for an offline callee becomes a push request`() {
        val ringExpiresAt = Instant.parse("2026-07-26T10:00:40Z")

        publish(
            KafkaTopics.CALL_SIGNAL,
            CallSignalEvent(
                callId = "call-offline",
                fromUserId = "alice-caller",
                signal = mapOf(
                    CallSignalKeys.VERB to CallSignalVerbs.INVITE,
                    CallSignalKeys.MEDIA to "video",
                    CallSignalKeys.SDP to "v=0",
                    CallSignalKeys.RING_EXPIRES_AT to ringExpiresAt.toString()
                ),
                recipientIds = listOf("bob-offline-call")
            )
        )

        val (key, request) = requestedNotifications().single()
        assertEquals("bob-offline-call", key, "keyed by recipient, like every other push request")
        assertEquals(NotificationRequestedEvent.KIND_INCOMING_CALL, request.kind)
        assertEquals("call-offline", request.payload[NotificationRequestedEvent.KEY_CALL_ID])
        assertEquals("alice-caller", request.payload[NotificationRequestedEvent.KEY_CALLER_ID])
        assertEquals("video", request.payload[NotificationRequestedEvent.KEY_MEDIA])
        assertEquals(
            ringExpiresAt.toString(),
            request.payload[NotificationRequestedEvent.KEY_RING_EXPIRES_AT],
            "a call push that arrives after the ring deadline must not raise an answer button"
        )
    }

    @Test
    fun `a callee with a live socket is rung by frame and not by push`() {
        val bob = sessionFor("bob-online-call")

        publish(
            KafkaTopics.CALL_SIGNAL,
            CallSignalEvent(
                callId = "call-online",
                fromUserId = "alice-caller-2",
                signal = mapOf(
                    CallSignalKeys.VERB to CallSignalVerbs.INVITE,
                    CallSignalKeys.MEDIA to "audio",
                    CallSignalKeys.RING_EXPIRES_AT to "2026-07-26T10:00:40Z"
                ),
                recipientIds = listOf("bob-online-call")
            )
        )

        assertIs<OutboundFrame.CallSignal>(bob.nextFrame())
        assertNoRequestedNotifications()
    }

    @Test
    fun `no push is requested for verbs other than an invite`() {
        publish(
            KafkaTopics.CALL_SIGNAL,
            CallSignalEvent(
                callId = "call-hangup",
                fromUserId = "alice-caller-3",
                signal = mapOf(CallSignalKeys.VERB to CallSignalVerbs.HANGUP),
                // Offline on purpose: only the verb decides, not reachability.
                recipientIds = listOf("bob-offline-hangup")
            )
        )

        assertNoRequestedNotifications()
    }

    @Test
    fun `a group invite pushes each offline invitee and labels the push as a group call`() {
        val carolOnline = sessionFor("carol-online-group")

        publish(
            KafkaTopics.CALL_SIGNAL,
            CallSignalEvent(
                callId = "group-call-1",
                fromUserId = "alice-group-caller",
                signal = mapOf(
                    CallSignalKeys.VERB to CallSignalVerbs.GROUP_INVITE,
                    CallSignalKeys.KIND to "group",
                    CallSignalKeys.MEDIA to "video",
                    CallSignalKeys.RING_EXPIRES_AT to "2026-07-26T10:00:40Z"
                ),
                recipientIds = listOf("bob-offline-group", "carol-online-group")
            )
        )

        // The invitee with a socket is rung by frame…
        assertIs<OutboundFrame.CallSignal>(carolOnline.nextFrame())

        // …and only the offline one becomes a push, carrying the group label through.
        val (key, request) = requestedNotifications().single()
        assertEquals("bob-offline-group", key)
        assertEquals(NotificationRequestedEvent.KIND_INCOMING_CALL, request.kind)
        assertEquals("group", request.payload[NotificationRequestedEvent.KEY_CALL_KIND])
        assertEquals("video", request.payload[NotificationRequestedEvent.KEY_MEDIA])
    }

    @Test
    fun `group roster deltas are relayed and never pushed`() {
        val alice = sessionFor("alice-roster")

        publish(
            KafkaTopics.CALL_SIGNAL,
            CallSignalEvent(
                callId = "group-call-2",
                fromUserId = "bob-roster",
                signal = mapOf(
                    CallSignalKeys.VERB to CallSignalVerbs.PARTICIPANT_JOINED,
                    CallSignalKeys.USER_ID to "bob-roster"
                ),
                // One recipient online, one offline: the offline one gets nothing, by design.
                recipientIds = listOf("alice-roster", "dave-offline-roster")
            )
        )

        assertEquals(
            CallSignalVerbs.PARTICIPANT_JOINED,
            assertIs<OutboundFrame.CallSignal>(alice.nextFrame()).signal[CallSignalKeys.VERB]
        )
        assertNoRequestedNotifications()
    }

    // ---- presence and typing ----

    @Test
    fun `a presence transition reaches the sessions subscribed to that user`() {
        val alice = sessionFor("alice-watcher")
        val carol = sessionFor("carol-unrelated")
        // Subscribed directly rather than through a presence.subscribe frame: the frame path needs a
        // membership lookup over HTTP, and what is under test here is the listener and the fan-out.
        subscriptions.subscribe(alice, "d-presence", setOf("bob-watched"))

        publish(
            KafkaTopics.PRESENCE_UPDATE,
            PresenceEvent("bob-watched", PresenceStatuses.OFFLINE, Instant.parse("2026-08-13T10:00:00Z"))
        )

        val update = assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame())
        assertEquals("bob-watched", update.userId)
        assertEquals("offline", update.status)
        assertEquals(Instant.parse("2026-08-13T10:00:00Z"), update.lastSeen)
        // Nobody else hears about it: presence goes to subscribers, not to everyone connected.
        assertNoFrames(carol)
    }

    @Test
    fun `a transition for a user nobody here watches is dropped, not delivered`() {
        val alice = sessionFor("alice-nonwatcher")

        publish(KafkaTopics.PRESENCE_UPDATE, PresenceEvent("bob-unwatched", PresenceStatuses.ONLINE))

        // Every node consumes every transition; a node holding no subscriber simply serves nothing.
        assertNoFrames(alice)
    }

    @Test
    fun `a typing indicator reaches the recipients the publishing node resolved`() {
        val bob = sessionFor("bob-typed-at")

        publish(
            KafkaTopics.TYPING_START,
            TypingEvent(
                dialogId = "d-typing",
                userId = "alice-typist",
                // Resolved by the publisher and already excluding the typist.
                recipientIds = listOf("bob-typed-at")
            )
        )

        val typing = assertIs<OutboundFrame.TypingStart>(bob.nextFrame())
        assertEquals("d-typing", typing.dialogId)
        assertEquals("alice-typist", typing.userId)
    }

    // ---- group changes ----

    private fun groupChanged(
        change: String,
        dialogId: String,
        actorId: String,
        recipients: List<String>,
        targetUserId: String? = null,
        messageId: String? = "m-sys-1"
    ) = MessageDeliveryEvent.GroupChanged(
        dialogId = dialogId,
        change = change,
        actorId = actorId,
        targetUserId = targetUserId,
        title = "team",
        messageId = messageId,
        sentAt = Instant.parse("2026-08-19T10:00:00Z"),
        recipientIds = recipients
    )

    @Test
    fun `a group change invalidates the cached membership and reaches everyone including the actor`() {
        val alice = sessionFor("alice-group")
        val bob = sessionFor("bob-group")
        val stub = (membershipClient as StubDialogMembershipClient)
            .withDialog("d-group-inv", "alice-group", "bob-group")
        membershipResolver.resolve("d-group-inv", "alice-group")
        val lookupsBefore = stub.lookups

        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            groupChanged(
                GroupChangeTypes.MEMBER_ADDED, "d-group-inv", actorId = "alice-group",
                recipients = listOf("alice-group", "bob-group", "carol-group"), targetUserId = "carol-group"
            )
        )

        // Both connected recipients get the frame — the actor's devices included, since the system
        // message has to render in their chat too.
        listOf(alice, bob).forEach { session ->
            val frame = assertIs<OutboundFrame.MessageSystem>(session.nextFrame())
            assertEquals("member_added", frame.kind, "translated to the wire vocabulary, not passed through")
            assertEquals("carol-group", frame.targetUserId)
            assertEquals("alice-group", frame.actorId)
            assertEquals("m-sys-1", frame.messageId)
        }

        // The frame having arrived proves the listener ran, and the listener invalidates first —
        // so this resolve must go back to the client instead of the cache.
        membershipResolver.resolve("d-group-inv", "alice-group")
        assertEquals(lookupsBefore + 1, stub.lookups, "the cached membership must not survive the change")
    }

    @Test
    fun `a removed member gets the frame and loses their presence watch on the dialog`() {
        val bob = sessionFor("bob-removed")
        subscriptions.subscribe(bob, "d-group-rm", setOf("alice-remover"))

        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            groupChanged(
                GroupChangeTypes.MEMBER_REMOVED, "d-group-rm", actorId = "alice-remover",
                recipients = listOf("alice-remover", "bob-removed"), targetUserId = "bob-removed"
            )
        )

        assertEquals(
            "member_removed",
            assertIs<OutboundFrame.MessageSystem>(bob.nextFrame()).kind,
            "the removed member needs the frame that says they are out"
        )
        assertEquals(
            emptyList(),
            subscriptions.subscribersOf("alice-remover").toList(),
            "their watch dies with the event, not with the cache TTL"
        )
    }

    @Test
    fun `a group deletion sends dialog-deleted and tears down the dialog's subscriptions`() {
        val alice = sessionFor("alice-del")
        subscriptions.subscribe(alice, "d-group-del", setOf("bob-del"))

        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            groupChanged(
                GroupChangeTypes.GROUP_DELETED, "d-group-del", actorId = "owner-del",
                recipients = listOf("alice-del"), messageId = null
            )
        )

        val frame = assertIs<OutboundFrame.DialogDeleted>(alice.nextFrame())
        assertEquals("d-group-del", frame.dialogId)
        assertEquals("owner-del", frame.actorId)
        assertEquals(emptyList(), subscriptions.subscribersOf("bob-del").toList())
    }

    @Test
    fun `a malformed event does not stall the partition behind it`() {
        val bob = sessionFor("bob-poison")

        // Both records are pinned to one partition on purpose. The claim under test is that the
        // poison record does not block what is queued *behind* it, and "behind" only exists
        // within a partition — spread across three, the good record could be consumed by another
        // thread entirely and the test would pass without proving anything.
        val partition = 0
        kafkaTemplate.send(KafkaTopics.MESSAGES_DELIVERY, partition, "poison", "{ not a valid event }").get()
        kafkaTemplate.send(
            KafkaTopics.MESSAGES_DELIVERY,
            partition,
            "poison",
            jsonMapper.writeValueAsString(
                accepted("someone", null, listOf("bob-poison"), clientMessageId = "c-after-poison")
            )
        ).get()

        bob.nextFrame().let { frame ->
            assertEquals("m-c-after-poison", assertIs<OutboundFrame.MessageNew>(frame).messageId)
        }
    }
}
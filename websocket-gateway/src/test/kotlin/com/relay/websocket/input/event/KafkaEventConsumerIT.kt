package com.relay.websocket.input.event

import com.relay.common.event.CallSignalEvent
import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageDeliveryEvent
import com.relay.common.event.NotificationCreatedEvent
import com.relay.common.event.NotificationRequestedEvent
import com.relay.common.model.UserPrincipal
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
import kotlin.test.assertTrue
import kotlin.test.fail
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
@EmbeddedKafka(
    partitions = 1,
    topics = [
        KafkaTopics.MESSAGES_DELIVERY,
        KafkaTopics.NOTIFICATIONS,
        KafkaTopics.NOTIFICATIONS_DELIVERY,
        KafkaTopics.CALL_SIGNAL
    ]
)
class KafkaEventConsumerIT {

    @Autowired private lateinit var registry: SessionRegistry
    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired private lateinit var jsonMapper: JsonMapper
    @Autowired private lateinit var endpoints: KafkaListenerEndpointRegistry
    @Autowired private lateinit var broker: EmbeddedKafkaBroker

    private val timeout = Duration.ofSeconds(30)
    private var counter = 0

    private lateinit var notificationRequests: Consumer<String, String>

    @BeforeTest
    fun waitForPartitionAssignment() {
        endpoints.listenerContainers.forEach { ContainerTestUtils.waitForAssignment(it, 1) }
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
    fun `a malformed event does not stall the partition behind it`() {
        val bob = sessionFor("bob-poison")

        kafkaTemplate.send(KafkaTopics.MESSAGES_DELIVERY, "{ not a valid event }").get()
        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            accepted("someone", null, listOf("bob-poison"), clientMessageId = "c-after-poison")
        )

        bob.nextFrame().let { frame ->
            assertEquals("m-c-after-poison", assertIs<OutboundFrame.MessageNew>(frame).messageId)
        }
    }
}
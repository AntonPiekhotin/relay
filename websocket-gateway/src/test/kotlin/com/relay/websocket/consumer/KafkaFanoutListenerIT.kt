package com.relay.websocket.consumer

import com.relay.common.event.CallSignalEvent
import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageCreatedEvent
import com.relay.common.event.NotificationCreatedEvent
import com.relay.common.model.UserPrincipal
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.RelaySession
import com.relay.websocket.session.SessionRegistry
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.ContainerTestUtils
import reactor.test.StepVerifier
import tools.jackson.databind.json.JsonMapper

/**
 * Exercises the real listeners against an in-JVM broker: publish an event, assert the frame
 * lands on a registered session. The WebSocket layer is deliberately out of scope here — this
 * covers Kafka wiring, decoding, and recipient routing.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        // Fresh consumer group per run, so start from the beginning of the topic rather than
        // racing partition assignment against the publish.
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"
    ]
)
@EmbeddedKafka(
    partitions = 1,
    topics = [KafkaTopics.MESSAGE_CREATED, KafkaTopics.NOTIFICATION_CREATED, KafkaTopics.CALL_SIGNAL]
)
class KafkaFanoutListenerIT {

    @Autowired private lateinit var registry: SessionRegistry
    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired private lateinit var jsonMapper: JsonMapper
    @Autowired private lateinit var endpoints: KafkaListenerEndpointRegistry

    private val timeout = Duration.ofSeconds(30)

    @BeforeTest
    fun waitForPartitionAssignment() {
        endpoints.listenerContainers.forEach { ContainerTestUtils.waitForAssignment(it, 1) }
    }

    private fun sessionFor(userId: String): RelaySession =
        RelaySession("s-$userId", UserPrincipal(userId, null, emptySet()), 32)
            .also(registry::register)

    private fun publish(topic: String, event: Any) {
        kafkaTemplate.send(topic, jsonMapper.writeValueAsString(event)).get()
    }

    @Test
    fun `pushes a created message to the recipient`() {
        val bob = sessionFor("bob-message")

        publish(
            KafkaTopics.MESSAGE_CREATED,
            MessageCreatedEvent(
                id = "m-1",
                chatId = "c-1",
                senderId = "alice",
                body = "hello bob",
                sentAt = Instant.parse("2026-07-26T10:00:00Z"),
                recipientIds = listOf("bob-message"),
                clientMessageId = "c-msg-1"
            )
        )

        StepVerifier.create(bob.frames)
            .assertNext { frame ->
                val message = assertIs<OutboundFrame.MessageNew>(frame)
                assertEquals("m-1", message.id)
                assertEquals("hello bob", message.body)
                assertEquals("alice", message.senderId)
                assertEquals(Instant.parse("2026-07-26T10:00:00Z"), message.sentAt)
                assertEquals("c-msg-1", message.clientMessageId, "echoed so the sender can reconcile")
            }
            .thenCancel()
            .verify(timeout)
    }

    @Test
    fun `pushes a notification with its untyped payload intact`() {
        val bob = sessionFor("bob-notification")

        publish(
            KafkaTopics.NOTIFICATION_CREATED,
            NotificationCreatedEvent(
                id = "n-1",
                kind = "FRIEND_REQUEST",
                payload = mapOf("fromUserId" to "alice"),
                createdAt = Instant.parse("2026-07-26T10:00:00Z"),
                recipientIds = listOf("bob-notification")
            )
        )

        StepVerifier.create(bob.frames)
            .assertNext { frame ->
                val notification = assertIs<OutboundFrame.Notification>(frame)
                assertEquals("FRIEND_REQUEST", notification.kind)
                assertEquals("alice", notification.payload["fromUserId"])
            }
            .thenCancel()
            .verify(timeout)
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

        StepVerifier.create(bob.frames)
            .assertNext { frame ->
                val signal = assertIs<OutboundFrame.CallSignal>(frame)
                assertEquals("call-1", signal.callId)
                assertEquals("offer", signal.signal["kind"])
                assertEquals("v=0...", signal.signal["sdp"])
            }
            .thenCancel()
            .verify(timeout)
    }

    @Test
    fun `a malformed event does not stall the partition behind it`() {
        val bob = sessionFor("bob-poison")

        kafkaTemplate.send(KafkaTopics.MESSAGE_CREATED, "{ not a valid event }").get()
        publish(
            KafkaTopics.MESSAGE_CREATED,
            MessageCreatedEvent(
                id = "m-after-poison",
                chatId = "c-1",
                senderId = "alice",
                body = "still delivered",
                sentAt = Instant.parse("2026-07-26T10:00:00Z"),
                recipientIds = listOf("bob-poison")
            )
        )

        StepVerifier.create(bob.frames)
            .assertNext { frame ->
                assertEquals("m-after-poison", assertIs<OutboundFrame.MessageNew>(frame).id)
            }
            .thenCancel()
            .verify(timeout)
    }
}
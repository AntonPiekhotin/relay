package com.relay.websocket.input.event

import com.relay.common.event.CallSignalEvent
import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageDeliveryEvent
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
    topics = [KafkaTopics.MESSAGES_DELIVERY, KafkaTopics.NOTIFICATIONS, KafkaTopics.CALL_SIGNAL]
)
class KafkaFanoutListenerIT {

    @Autowired private lateinit var registry: SessionRegistry
    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired private lateinit var jsonMapper: JsonMapper
    @Autowired private lateinit var endpoints: KafkaListenerEndpointRegistry

    private val timeout = Duration.ofSeconds(30)
    private var counter = 0

    @BeforeTest
    fun waitForPartitionAssignment() {
        endpoints.listenerContainers.forEach { ContainerTestUtils.waitForAssignment(it, 1) }
    }

    private fun sessionFor(userId: String): RelaySession =
        RelaySession("s-$userId-${counter++}", UserPrincipal(userId, null, emptySet()), 32)
            .also(registry::register)

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

        StepVerifier.create(alice.frames)
            .assertNext { frame ->
                val ack = assertIs<OutboundFrame.Ack>(frame)
                assertEquals("c-1", ack.clientMsgId)
                assertEquals("m-c-1", ack.messageId)
            }
            .thenCancel()
            .verify(timeout)

        StepVerifier.create(bob.frames)
            .assertNext { frame ->
                val message = assertIs<OutboundFrame.MessageNew>(frame)
                assertEquals("hello", message.text)
                assertEquals("alice-ack", message.senderId)
            }
            .thenCancel()
            .verify(timeout)
    }

    @Test
    fun `the sender's other device gets message-new, the sending device only the ack`() {
        val sendingDevice = sessionFor("alice-multi")
        val otherDevice = sessionFor("alice-multi")

        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            accepted("alice-multi", sendingDevice.sessionId, listOf("alice-multi"))
        )

        StepVerifier.create(otherDevice.frames)
            .assertNext { assertIs<OutboundFrame.MessageNew>(it) }
            .thenCancel()
            .verify(timeout)

        StepVerifier.create(sendingDevice.frames)
            .assertNext { assertIs<OutboundFrame.Ack>(it) }
            .thenCancel()
            .verify(timeout)
    }

    @Test
    fun `a duplicate outcome acks the sender but fans out nothing`() {
        val alice = sessionFor("alice-dup")
        val bob = sessionFor("bob-dup")

        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            accepted("alice-dup", alice.sessionId, emptyList(), clientMessageId = "c-dup", duplicate = true)
        )

        StepVerifier.create(alice.frames)
            .assertNext { assertEquals("c-dup", assertIs<OutboundFrame.Ack>(it).clientMsgId) }
            .thenCancel()
            .verify(timeout)

        bob.complete()
        StepVerifier.create(bob.frames).verifyComplete()
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

        StepVerifier.create(mallory.frames)
            .assertNext { frame ->
                val error = assertIs<OutboundFrame.Error>(frame)
                assertEquals("NOT_A_PARTICIPANT", error.code)
                assertEquals("c-403", error.refId)
            }
            .thenCancel()
            .verify(timeout)
    }

    @Test
    fun `pushes a notification with its untyped payload intact`() {
        val bob = sessionFor("bob-notification")

        publish(
            KafkaTopics.NOTIFICATIONS,
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
                assertEquals("alice", notification.data["fromUserId"])
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
            }
            .thenCancel()
            .verify(timeout)
    }

    @Test
    fun `a malformed event does not stall the partition behind it`() {
        val bob = sessionFor("bob-poison")

        kafkaTemplate.send(KafkaTopics.MESSAGES_DELIVERY, "{ not a valid event }").get()
        publish(
            KafkaTopics.MESSAGES_DELIVERY,
            accepted("someone", null, listOf("bob-poison"), clientMessageId = "c-after-poison")
        )

        StepVerifier.create(bob.frames)
            .assertNext { frame ->
                assertEquals("m-c-after-poison", assertIs<OutboundFrame.MessageNew>(frame).messageId)
            }
            .thenCancel()
            .verify(timeout)
    }
}
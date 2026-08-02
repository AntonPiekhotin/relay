package com.relay.message.service

import com.relay.common.dto.SendMessageRequest
import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageDeliveryEvent
import com.relay.common.event.SendMessageCommand
import com.relay.common.exception.RelayException
import com.relay.message.PostgresTestcontainerConfig
import com.relay.message.model.dto.CreateDialogRequest
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import tools.jackson.databind.json.JsonMapper

/**
 * Covers the invariants the send design rests on: recipients resolved server-side from dialog
 * membership, idempotency on (senderId, clientMessageId), and — for the event-driven path —
 * that EVERY command produces exactly one delivery event, because a send with no event leaves
 * the client stuck in "sending".
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"
    ]
)
@Import(PostgresTestcontainerConfig::class)
@EmbeddedKafka(partitions = 1, topics = [KafkaTopics.MESSAGES_INCOMING, KafkaTopics.MESSAGES_DELIVERY])
class MessageServiceIT {

    @Autowired private lateinit var messageService: MessageService
    @Autowired private lateinit var dialogService: DialogService
    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired private lateinit var broker: EmbeddedKafkaBroker
    @Autowired private lateinit var jsonMapper: JsonMapper

    private lateinit var deliveries: Consumer<String, String>

    @BeforeTest
    fun subscribeToDeliveries() {
        val props = KafkaTestUtils.consumerProps(broker, "message-it-${UUID.randomUUID()}", true)
            .toMutableMap()
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        deliveries = DefaultKafkaConsumerFactory(props, StringDeserializer(), StringDeserializer())
            .createConsumer()
        deliveries.subscribe(listOf(KafkaTopics.MESSAGES_DELIVERY))
        deliveries.poll(Duration.ofMillis(200))
    }

    @AfterTest
    fun closeConsumer() {
        deliveries.close()
    }

    private fun deliveryEvents(): List<MessageDeliveryEvent> =
        KafkaTestUtils.getRecords(deliveries, Duration.ofSeconds(10))
            .records(KafkaTopics.MESSAGES_DELIVERY)
            .map { jsonMapper.readValue(it.value(), MessageDeliveryEvent::class.java) }

    private fun dialogOf(vararg participants: String): String =
        dialogService.create(CreateDialogRequest(participants.toSet())).id

    private fun send(dialogId: String, senderId: String, clientMessageId: String, text: String = "hi") =
        messageService.send(SendMessageRequest(clientMessageId, dialogId, senderId, text))

    private fun sendCommand(
        dialogId: String,
        senderId: String,
        clientMessageId: String,
        sessionId: String = "sess-1",
        text: String = "hi"
    ) {
        val command = SendMessageCommand(clientMessageId, dialogId, senderId, sessionId, text)
        kafkaTemplate.send(KafkaTopics.MESSAGES_INCOMING, dialogId, jsonMapper.writeValueAsString(command)).get()
    }

    // ---- service-level invariants (shared by both transports) ----

    @Test
    fun `stores a message and announces it to every participant including the sender`() {
        val dialogId = dialogOf("alice", "bob")

        val result = send(dialogId, senderId = "alice", clientMessageId = "c-1", text = "hello bob")

        assertTrue(result.created)
        assertEquals("hello bob", result.message.text)

        val event = assertIs<MessageDeliveryEvent.Accepted>(deliveryEvents().single())
        assertEquals(result.message.id, event.messageId)
        assertEquals("c-1", event.clientMessageId)
        assertTrue(!event.duplicate)
        assertEquals(setOf("alice", "bob"), event.recipientIds.toSet())
    }

    @Test
    fun `recognises a repeated clientMessageId and does not announce it twice`() {
        val dialogId = dialogOf("alice", "bob")

        val first = send(dialogId, "alice", clientMessageId = "c-dup")
        val second = send(dialogId, "alice", clientMessageId = "c-dup")

        assertTrue(first.created)
        assertTrue(!second.created)
        assertEquals(first.message.id, second.message.id)
        assertEquals(1, deliveryEvents().size, "re-announcing would push the message to everyone twice")
    }

    @Test
    fun `dedup key is sender-scoped - same clientMessageId from another sender is distinct`() {
        val dialogId = dialogOf("alice", "bob")

        val a = send(dialogId, "alice", clientMessageId = "c-shared")
        val b = send(dialogId, "bob", clientMessageId = "c-shared")

        assertTrue(a.created)
        assertTrue(b.created, "the constraint is (sender_id, client_message_id)")
        assertTrue(a.message.id != b.message.id)
    }

    @Test
    fun `refuses a sender who is not a participant`() {
        val dialogId = dialogOf("alice", "bob")

        val ex = assertFailsWith<RelayException> { send(dialogId, senderId = "mallory", clientMessageId = "c-2") }

        assertEquals(403, ex.statusCode)
        assertTrue(deliveryEvents().isEmpty(), "nothing is announced for a refused send")
    }

    @Test
    fun `refuses an unknown dialog`() {
        val ex = assertFailsWith<RelayException> { send(UUID.randomUUID().toString(), "alice", "c-3") }
        assertEquals(404, ex.statusCode)
    }

    // ---- the event-driven path (messages.incoming -> messages.delivery) ----

    @Test
    fun `a send command is persisted and answered with Accepted carrying the sender session`() {
        val dialogId = dialogOf("alice", "bob")

        sendCommand(dialogId, "alice", clientMessageId = "k-1", sessionId = "sess-42", text = "over kafka")

        val event = assertIs<MessageDeliveryEvent.Accepted>(deliveryEvents().single())
        assertEquals("k-1", event.clientMessageId)
        assertEquals("sess-42", event.senderSessionId, "the ack must reach the device that sent")
        assertEquals("over kafka", event.text)
        assertEquals(setOf("alice", "bob"), event.recipientIds.toSet())
    }

    @Test
    fun `a retried command is answered with a duplicate Accepted and no second fan-out`() {
        val dialogId = dialogOf("alice", "bob")

        sendCommand(dialogId, "alice", clientMessageId = "k-dup")
        val first = assertIs<MessageDeliveryEvent.Accepted>(deliveryEvents().single())

        sendCommand(dialogId, "alice", clientMessageId = "k-dup")
        val second = assertIs<MessageDeliveryEvent.Accepted>(deliveryEvents().single())

        assertEquals(first.messageId, second.messageId)
        assertTrue(second.duplicate)
        assertTrue(second.recipientIds.isEmpty(), "a duplicate acks the sender but fans out to nobody")
    }

    @Test
    fun `a command for a dialog the sender is not in is Rejected with a code`() {
        val dialogId = dialogOf("alice", "bob")

        sendCommand(dialogId, senderId = "mallory", clientMessageId = "k-403", sessionId = "sess-m")

        val event = assertIs<MessageDeliveryEvent.Rejected>(deliveryEvents().single())
        assertEquals("NOT_A_PARTICIPANT", event.code)
        assertEquals("k-403", event.clientMessageId)
        assertEquals("sess-m", event.senderSessionId)
    }

    @Test
    fun `a malformed command does not stall the partition behind it`() {
        val dialogId = dialogOf("alice", "bob")

        kafkaTemplate.send(KafkaTopics.MESSAGES_INCOMING, "{ not a command }").get()
        sendCommand(dialogId, "alice", clientMessageId = "k-after-poison")

        val event = assertIs<MessageDeliveryEvent.Accepted>(deliveryEvents().single())
        assertEquals("k-after-poison", event.clientMessageId)
    }
}
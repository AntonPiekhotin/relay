package com.relay.message.service

import com.relay.common.dto.SendMessageRequest
import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageCreatedEvent
import com.relay.common.exception.RelayException
import com.relay.message.dto.CreateChatRequest
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import tools.jackson.databind.json.JsonMapper

/**
 * Covers the two invariants the whole send design rests on: recipients are resolved server-side
 * from chat membership, and a repeated clientMessageId does not produce a second message.
 *
 * H2 in PostgreSQL mode stands in for Postgres, and the schema is generated, so the unique
 * constraint under test is the real one Hibernate emits.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:messagedb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"
    ]
)
@EmbeddedKafka(partitions = 1, topics = [KafkaTopics.MESSAGE_CREATED])
class MessageServiceIT {

    @Autowired private lateinit var messageService: MessageService
    @Autowired private lateinit var chatService: ChatService
    @Autowired private lateinit var broker: EmbeddedKafkaBroker
    @Autowired private lateinit var jsonMapper: JsonMapper

    private lateinit var announcements: Consumer<String, String>

    @BeforeTest
    fun subscribeToAnnouncements() {
        val props = KafkaTestUtils.consumerProps(broker, "message-it-${UUID.randomUUID()}", true)
            .toMutableMap()
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        announcements = DefaultKafkaConsumerFactory(props, StringDeserializer(), StringDeserializer())
            .createConsumer()
        announcements.subscribe(listOf(KafkaTopics.MESSAGE_CREATED))
        // Force assignment before the test publishes anything.
        announcements.poll(Duration.ofMillis(200))
    }

    @AfterTest
    fun closeConsumer() {
        announcements.close()
    }

    private fun announced(): List<MessageCreatedEvent> =
        KafkaTestUtils.getRecords(announcements, Duration.ofSeconds(10))
            .records(KafkaTopics.MESSAGE_CREATED)
            .map { jsonMapper.readValue(it.value(), MessageCreatedEvent::class.java) }

    private fun chatOf(vararg participants: String): String =
        chatService.create(CreateChatRequest(participants.toSet())).id

    private fun send(chatId: String, senderId: String, clientMessageId: String, body: String = "hi") =
        messageService.send(SendMessageRequest(clientMessageId, chatId, senderId, body))

    @Test
    fun `stores a message and announces it to every participant including the sender`() {
        val chatId = chatOf("alice", "bob")

        val result = send(chatId, senderId = "alice", clientMessageId = "c-1", body = "hello bob")

        assertTrue(result.created)
        assertEquals("hello bob", result.message.body)
        assertEquals("alice", result.message.senderId)

        val events = announced()
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(result.message.id, event.id)
        assertEquals("c-1", event.clientMessageId, "echoed so the sender can reconcile its send")
        assertEquals(
            setOf("alice", "bob"),
            event.recipientIds.toSet(),
            "the sender's own other devices need the message too"
        )
    }

    @Test
    fun `returns the stored message for a repeated clientMessageId and announces it once`() {
        val chatId = chatOf("alice", "bob")

        val first = send(chatId, "alice", clientMessageId = "c-dup")
        val second = send(chatId, "alice", clientMessageId = "c-dup")

        assertTrue(first.created)
        assertTrue(!second.created, "a repeated send is recognised, not stored again")
        assertEquals(first.message.id, second.message.id)

        assertEquals(
            1,
            announced().size,
            "re-announcing would push the same message to every client twice"
        )
    }

    @Test
    fun `treats the same clientMessageId in a different chat as a distinct message`() {
        val firstChat = chatOf("alice", "bob")
        val secondChat = chatOf("alice", "carol")

        val a = send(firstChat, "alice", clientMessageId = "c-shared")
        val b = send(secondChat, "alice", clientMessageId = "c-shared")

        assertTrue(a.created)
        assertTrue(b.created, "the constraint is scoped per chat")
        assertTrue(a.message.id != b.message.id)
    }

    @Test
    fun `refuses a sender who is not a participant`() {
        val chatId = chatOf("alice", "bob")

        val ex = assertFailsWith<RelayException> { send(chatId, senderId = "mallory", clientMessageId = "c-2") }

        assertEquals(403, ex.statusCode)
        assertTrue(announced().isEmpty(), "nothing is announced for a refused send")
    }

    @Test
    fun `refuses an unknown chat`() {
        val ex = assertFailsWith<RelayException> {
            send(UUID.randomUUID().toString(), "alice", "c-3")
        }

        assertEquals(404, ex.statusCode)
    }

    @Test
    fun `refuses a chat id that is not a uuid`() {
        val ex = assertFailsWith<RelayException> { send("not-a-uuid", "alice", "c-4") }

        assertEquals(400, ex.statusCode)
        assertNotNull(ex.message)
    }
}
package com.relay.message.service

import com.relay.common.event.KafkaTopics
import com.relay.common.event.MarkReadCommand
import com.relay.common.event.MessageDeliveryEvent
import com.relay.common.exception.RelayException
import com.relay.message.PostgresTestcontainerConfig
import com.relay.message.model.Message
import com.relay.message.repository.MessageRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * The read cursor, and the property everything else about it depends on: it only ever moves forward.
 *
 * Read commands are fire-and-forget over Kafka, clients retry them freely, and two devices of the
 * same account read the same conversation at the same moment — so a command carrying an older
 * position *will* arrive. If it moved the cursor back, messages the user has already seen would
 * reappear as unread, which is the kind of bug that looks like data loss to a user.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"
    ]
)
@Import(PostgresTestcontainerConfig::class)
@EmbeddedKafka(partitions = 1, topics = [KafkaTopics.MESSAGES_READ, KafkaTopics.MESSAGES_DELIVERY])
class ReadStateServiceIT {

    @Autowired private lateinit var readStateService: ReadStateService
    @Autowired private lateinit var dialogService: DialogService
    @Autowired private lateinit var dialogQueryService: DialogQueryService
    @Autowired private lateinit var messageRepository: MessageRepository
    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired private lateinit var broker: EmbeddedKafkaBroker
    @Autowired private lateinit var jsonMapper: JsonMapper

    private lateinit var deliveries: Consumer<String, String>

    private val base: Instant = Instant.parse("2026-07-26T10:00:00Z")

    @BeforeTest
    fun subscribeToDeliveries() {
        val props = KafkaTestUtils.consumerProps(broker, "read-it-${UUID.randomUUID()}", true).toMutableMap()
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

    private fun userId(role: String) = "$role-${UUID.randomUUID()}"

    private fun dialogOf(a: String, b: String): UUID =
        UUID.fromString(dialogService.openDirect(a, b).dialog.id)

    private fun insert(dialogId: UUID, senderId: String, sentAt: Instant): Message =
        messageRepository.saveAndFlush(
            Message(
                dialogId = dialogId,
                senderId = senderId,
                text = "m",
                clientMessageId = UUID.randomUUID().toString(),
                sentAt = sentAt
            )
        )

    private fun markRead(dialogId: UUID, reader: String, upTo: UUID, session: String = "s-1") =
        readStateService.markRead(
            MarkReadCommand(
                dialogId = dialogId.toString(),
                readerId = reader,
                readerSessionId = session,
                upToMessageId = upTo.toString()
            )
        )

    private fun unreadFor(user: String, dialogId: UUID): Long =
        dialogQueryService.metadata(user, dialogId.toString()).unreadCount

    @Test
    fun `unread counts only the other party's messages, and clears as the cursor advances`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)
        val first = insert(dialog, alice, base)
        val second = insert(dialog, alice, base.plusSeconds(1))
        val third = insert(dialog, alice, base.plusSeconds(2))

        assertEquals(3, unreadFor(bob, dialog))
        assertEquals(0, unreadFor(alice, dialog), "your own messages are never unread to you")

        assertNotNull(markRead(dialog, bob, second.id))
        assertEquals(1, unreadFor(bob, dialog), "only what is past the cursor")

        assertNotNull(markRead(dialog, bob, third.id))
        assertEquals(0, unreadFor(bob, dialog))
        // `first` is behind the cursor and stays read; nothing here re-counts it.
        assertEquals(0, unreadFor(bob, dialog), "and re-reading the count does not resurrect ${first.id}")
    }

    @Test
    fun `a repeated read is a no-op with no receipt`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)
        val message = insert(dialog, alice, base)

        assertNotNull(markRead(dialog, bob, message.id), "the first read moved the cursor")
        assertNull(markRead(dialog, bob, message.id), "the second must not fire a second read tick")
    }

    @Test
    fun `a late command carrying an older position cannot drag the cursor backwards`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)
        val older = insert(dialog, alice, base)
        val newer = insert(dialog, alice, base.plusSeconds(1))

        assertNotNull(markRead(dialog, bob, newer.id))
        assertEquals(0, unreadFor(bob, dialog))

        // Arrives after the newer one — a retry, or the user's other device. Kafka orders reads within
        // a dialog, but a client can still emit them out of order, so the database has to refuse.
        assertNull(markRead(dialog, bob, older.id), "no receipt for a cursor that did not move")
        assertEquals(0, unreadFor(bob, dialog), "and the read messages stay read")
    }

    @Test
    fun `advances past messages sharing one timestamp`() {
        // The read cursor compares `(last_read_at, last_read_id)` for the same reason the history
        // cursors do: on `last_read_at` alone, a chat whose last three messages landed in the same
        // microsecond would sit at 2 unread forever.
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)
        val simultaneous = (1..3).map { insert(dialog, alice, base) }
        assertEquals(3, unreadFor(bob, dialog))

        // Read them in stored order — whichever of the three sorts last is the furthest position.
        val furthest = simultaneous.maxBy { it.id.toString() }
        assertNotNull(markRead(dialog, bob, furthest.id))

        assertEquals(0, unreadFor(bob, dialog))
    }

    @Test
    fun `the receipt names the whole dialog and the device that read`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)
        val message = insert(dialog, alice, base)

        val receipt = assertNotNull(markRead(dialog, bob, message.id, session = "bob-phone"))

        assertEquals(bob, receipt.readerId)
        assertEquals("bob-phone", receipt.readerSessionId, "the gateway skips this device on fan-out")
        assertEquals(message.id.toString(), receipt.upToMessageId)
        assertEquals(
            setOf(alice, bob),
            receipt.recipientIds.toSet(),
            "alice draws read ticks; bob's other devices clear their badge"
        )
    }

    @Test
    fun `refuses a reader who is not in the dialog`() {
        val dialog = dialogOf(userId("alice"), userId("bob"))
        val message = insert(dialog, userId("alice"), base)

        val ex = assertFailsWith<RelayException> { markRead(dialog, userId("mallory"), message.id) }
        assertEquals(404, ex.statusCode)
    }

    @Test
    fun `refuses a position borrowed from another dialog`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val ours = dialogOf(alice, bob)
        val theirs = dialogOf(alice, userId("carol"))
        val elsewhere = insert(theirs, alice, base)

        val ex = assertFailsWith<RelayException> { markRead(ours, bob, elsewhere.id) }
        assertEquals(400, ex.statusCode)
    }

    @Test
    fun `a read command off the topic becomes a receipt on messages-delivery`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)
        val message = insert(dialog, alice, base)

        val command = MarkReadCommand(dialog.toString(), bob, "bob-phone", message.id.toString())
        kafkaTemplate.send(KafkaTopics.MESSAGES_READ, dialog.toString(), jsonMapper.writeValueAsString(command)).get()

        val events = KafkaTestUtils.getRecords(deliveries, Duration.ofSeconds(10))
            .records(KafkaTopics.MESSAGES_DELIVERY)
            .map { jsonMapper.readValue(it.value(), MessageDeliveryEvent::class.java) }

        val receipt = assertIs<MessageDeliveryEvent.Read>(events.single())
        assertEquals(bob, receipt.readerId)
        assertEquals(message.id.toString(), receipt.upToMessageId)
    }
}

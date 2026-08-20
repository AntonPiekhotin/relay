package com.relay.message.service

import com.relay.common.dto.SendMessageRequest
import com.relay.common.event.KafkaTopics
import com.relay.common.exception.RelayException
import com.relay.message.PostgresTestcontainerConfig
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.context.EmbeddedKafka

/**
 * The dialog list — the endpoint that makes a conversation discoverable by the *other* person.
 *
 * Before it, a dialog id existed only on the device that opened it: Alice could open a chat with Bob
 * and send while he was offline, and Bob would get a push and then have nothing to fetch. The
 * conversation was invisible to him until he happened to be connected for a live `message.new`.
 *
 * Kafka is here only because `MessageService.send` announces its outcome; the sends are what maintain
 * `last_message_at`, which is what the ordering is built on.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"
    ]
)
@Import(PostgresTestcontainerConfig::class)
@EmbeddedKafka(partitions = 1, topics = [KafkaTopics.MESSAGES_DELIVERY])
class DialogQueryServiceIT {

    @Autowired private lateinit var dialogQueryService: DialogQueryService
    @Autowired private lateinit var dialogService: DialogService
    @Autowired private lateinit var messageService: MessageService

    private fun userId(role: String) = "$role-${UUID.randomUUID()}"

    private fun dialogOf(a: String, b: String): String = dialogService.openDirect(a, b).dialog.id

    private fun send(dialogId: String, senderId: String, text: String = "hi") =
        messageService.send(SendMessageRequest(UUID.randomUUID().toString(), dialogId, senderId, text))

    @Test
    fun `lists the caller's dialogs most recently active first`() {
        val alice = userId("alice")
        val first = dialogOf(alice, userId("bob"))
        val second = dialogOf(alice, userId("carol"))

        // Reverse of creation order, so passing cannot be an accident of insertion order.
        send(second, alice)
        send(first, alice)

        val listed = dialogQueryService.list(alice).dialogs.map { it.dialogId }

        assertEquals(listOf(first, second), listed)
    }

    @Test
    fun `a send moves the dialog to the top and stamps lastMessageAt`() {
        val alice = userId("alice")
        val quiet = dialogOf(alice, userId("bob"))
        val busy = dialogOf(alice, userId("carol"))
        send(quiet, alice)
        assertEquals(quiet, dialogQueryService.list(alice).dialogs.first().dialogId)

        val result = send(busy, alice)

        val top = dialogQueryService.list(alice).dialogs.first()
        assertEquals(busy, top.dialogId)
        assertEquals(
            result.message.sentAt, top.lastMessageAt,
            "the column has to agree with the message that set it"
        )
    }

    @Test
    fun `an unused dialog has no lastMessageAt and sorts below active ones`() {
        val alice = userId("alice")
        val unused = dialogOf(alice, userId("bob"))
        val active = dialogOf(alice, userId("carol"))
        send(active, alice)

        val listed = dialogQueryService.list(alice).dialogs

        assertEquals(listOf(active, unused), listed.map { it.dialogId }, "never is not the same as long ago")
        assertNull(listed.last().lastMessageAt)
        assertNotNull(listed.first().lastMessageAt)
    }

    @Test
    fun `unread counts are per caller on the same dialog`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)
        send(dialog, alice)
        send(dialog, alice)

        val forBob = dialogQueryService.list(bob).dialogs.single()
        val forAlice = dialogQueryService.list(alice).dialogs.single()

        assertEquals(2, forBob.unreadCount)
        assertEquals(0, forAlice.unreadCount, "the sender has nothing unread in their own conversation")
    }

    @Test
    fun `carries the membership a client needs to name the conversation`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)

        val row = dialogQueryService.list(bob).dialogs.single()

        assertEquals(dialog, row.dialogId)
        assertEquals("direct", row.type)
        // `type` is `direct` and there is no title, so subtracting yourself from this is the only way
        // a client knows whose chat it is.
        assertEquals(setOf(alice, bob), row.participantIds.toSet())
    }

    @Test
    fun `shows only the caller's own dialogs`() {
        val alice = userId("alice")
        val mallory = userId("mallory")
        dialogOf(alice, userId("bob"))

        assertTrue(dialogQueryService.list(mallory).dialogs.isEmpty())
    }

    @Test
    fun `the single-dialog lookup is a 404 for a non-participant`() {
        val dialog = dialogOf(userId("alice"), userId("bob"))

        val ex = assertFailsWith<RelayException> { dialogQueryService.metadata(userId("mallory"), dialog) }
        assertEquals(404, ex.statusCode)
    }

    /**
     * The property that makes keyset pagination worth its complexity: walking the pages visits
     * every dialog exactly once, in the same order the unpaginated list had — including the seam
     * between "active" rows and null-`lastMessageAt` rows, which is where a broken cursor predicate
     * drops or repeats.
     */
    @Test
    fun `paging walks every dialog exactly once across the null-lastMessageAt seam`() {
        val alice = userId("alice")
        val active = (1..3).map { dialogOf(alice, userId("peer$it")) }
        repeat(2) { dialogOf(alice, userId("quiet$it")) } // never used — null lastMessageAt
        active.forEach { send(it, alice) }

        val fullOrder = dialogQueryService.list(alice).dialogs.map { it.dialogId }
        assertEquals(5, fullOrder.size)

        val walked = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = dialogQueryService.list(alice, cursor = cursor, limit = 2)
            walked += page.dialogs.map { it.dialogId }
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(fullOrder, walked, "pages concatenate to the whole list, no repeats, no gaps")
    }

    @Test
    fun `nextCursor is null on a short page and present on a full one`() {
        val alice = userId("alice")
        repeat(3) { dialogOf(alice, userId("peer$it")) }

        val full = dialogQueryService.list(alice, cursor = null, limit = 3)
        val short = dialogQueryService.list(alice, cursor = null, limit = 4)

        assertNotNull(full.nextCursor, "a full page means there is probably more")
        assertNull(short.nextCursor)
    }

    @Test
    fun `a cursor naming a dialog that is not the caller's is a 400`() {
        val alice = userId("alice")
        val mallory = userId("mallory")
        dialogOf(alice, userId("bob"))
        val someoneElses = dialogOf(mallory, userId("eve"))

        assertEquals(400, assertFailsWith<RelayException> {
            dialogQueryService.list(alice, cursor = someoneElses, limit = 10)
        }.statusCode, "a pagination parameter was wrong; nothing was read at that id")
        assertEquals(400, assertFailsWith<RelayException> {
            dialogQueryService.list(alice, cursor = UUID.randomUUID().toString(), limit = 10)
        }.statusCode)
        assertEquals(400, assertFailsWith<RelayException> {
            dialogQueryService.list(alice, cursor = "not-a-uuid", limit = 10)
        }.statusCode)
    }
}

package com.relay.message.service

import com.relay.common.exception.RelayException
import com.relay.message.PostgresTestcontainerConfig
import com.relay.message.model.Message
import com.relay.message.model.dto.HistoryMessageResponse
import com.relay.message.repository.MessageRepository
import java.time.Instant
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

/**
 * Cursor pagination, which is the whole point of this endpoint and the one part of it that is easy
 * to get quietly wrong.
 *
 * The tests that matter here are the ones about **identical timestamps**. Rapid sends land in the
 * same microsecond, and a cursor that compares `sent_at` alone either skips those rows or repeats
 * them forever — neither of which shows up in a test that inserts messages a second apart. History
 * is the only copy of a conversation a client can recover, so a page boundary that drops a row drops
 * it permanently.
 *
 * No Kafka here: these are read paths, and inserts go through the repository so timestamps can be
 * pinned exactly. `MessageServiceIT` covers the send path and its events.
 */
@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Import(PostgresTestcontainerConfig::class)
class MessageHistoryServiceIT {

    @Autowired private lateinit var historyService: MessageHistoryService
    @Autowired private lateinit var dialogService: DialogService
    @Autowired private lateinit var messageRepository: MessageRepository

    private fun userId(role: String) = "$role-${UUID.randomUUID()}"

    /** Microsecond precision, because `timestamp(6)` truncates and the assertions compare instants. */
    private val base: Instant = Instant.parse("2026-07-26T10:00:00Z")

    private fun dialogOf(a: String, b: String): UUID =
        UUID.fromString(dialogService.openDirect(a, b).dialog.id)

    private fun insert(dialogId: UUID, senderId: String, text: String, sentAt: Instant): Message =
        messageRepository.saveAndFlush(
            Message(
                dialogId = dialogId,
                senderId = senderId,
                text = text,
                clientMessageId = UUID.randomUUID().toString(),
                sentAt = sentAt
            )
        )

    private fun List<HistoryMessageResponse>.texts() = map { it.text }

    @Test
    fun `opens on the newest page, newest first`() {
        val alice = userId("alice")
        val dialog = dialogOf(alice, userId("bob"))
        (1..5).forEach { insert(dialog, alice, "m$it", base.plusSeconds(it.toLong())) }

        val page = historyService.history(alice, dialog.toString(), before = null, after = null, limit = 3)

        assertEquals(listOf("m5", "m4", "m3"), page.messages.texts())
        assertNotNull(page.nextCursor, "a full page offers the next one")
    }

    @Test
    fun `pages backwards through the whole conversation with no gap and no repeat`() {
        val alice = userId("alice")
        val dialog = dialogOf(alice, userId("bob"))
        (1..7).forEach { insert(dialog, alice, "m$it", base.plusSeconds(it.toLong())) }

        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = historyService.history(alice, dialog.toString(), before = cursor, after = null, limit = 2)
            seen += page.messages.texts()
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(listOf("m7", "m6", "m5", "m4", "m3", "m2", "m1"), seen)
    }

    @Test
    fun `walks messages sharing one timestamp exactly once`() {
        // The case a `sent_at`-only cursor cannot survive: with three messages at the same instant, a
        // strict comparison on the timestamp alone skips the other two, and a non-strict one returns
        // the same row forever. Only the row value `(sent_at, id)` separates them.
        val alice = userId("alice")
        val dialog = dialogOf(alice, userId("bob"))
        val simultaneous = (1..3).map { insert(dialog, alice, "same$it", base) }

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = historyService.history(alice, dialog.toString(), before = cursor, after = null, limit = 1)
            seen += page.messages.map { it.messageId }
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < 10)

        assertEquals(
            simultaneous.map { it.id.toString() }.toSet(),
            seen.toSet(),
            "every message at the shared timestamp must appear"
        )
        assertEquals(seen.size, seen.toSet().size, "and none of them twice")
    }

    @Test
    fun `after recovers exactly the messages missed while disconnected, oldest first`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)
        val lastSeen = insert(dialog, alice, "before-drop", base)
        (1..3).forEach { insert(dialog, bob, "missed$it", base.plusSeconds(it.toLong())) }

        val page = historyService.history(
            alice, dialog.toString(), before = null, after = lastSeen.id.toString(), limit = 50
        )

        assertEquals(
            listOf("missed1", "missed2", "missed3"),
            page.messages.texts(),
            "ascending, and exclusive of the message the client already had"
        )
        assertNull(page.nextCursor, "a short page is the end of the gap")
    }

    @Test
    fun `a cursor is exclusive of the message it names`() {
        val alice = userId("alice")
        val dialog = dialogOf(alice, userId("bob"))
        val first = insert(dialog, alice, "m1", base)
        insert(dialog, alice, "m2", base.plusSeconds(1))

        val backwards = historyService.history(
            alice, dialog.toString(), before = first.id.toString(), after = null, limit = 50
        )
        assertTrue(backwards.messages.isEmpty(), "nothing precedes the oldest message")
    }

    @Test
    fun `clamps an oversized limit instead of rejecting it`() {
        val alice = userId("alice")
        val dialog = dialogOf(alice, userId("bob"))
        (1..3).forEach { insert(dialog, alice, "m$it", base.plusSeconds(it.toLong())) }

        val page = historyService.history(alice, dialog.toString(), before = null, after = null, limit = 10_000)

        assertEquals(3, page.messages.size)
    }

    @Test
    fun `exposes clientMsgId only on the caller's own messages`() {
        val alice = userId("alice")
        val bob = userId("bob")
        val dialog = dialogOf(alice, bob)
        insert(dialog, alice, "mine", base)
        insert(dialog, bob, "theirs", base.plusSeconds(1))

        val page = historyService.history(alice, dialog.toString(), before = null, after = null, limit = 50)

        val byText = page.messages.associateBy { it.text }
        assertNotNull(byText["mine"]?.clientMsgId, "the caller merges this against its own outbox")
        assertNull(byText["theirs"]?.clientMsgId, "somebody else's idempotency key is not the caller's business")
    }

    @Test
    fun `refuses both cursors at once rather than silently picking one`() {
        val alice = userId("alice")
        val dialog = dialogOf(alice, userId("bob"))
        val message = insert(dialog, alice, "m1", base)

        val ex = assertFailsWith<RelayException> {
            historyService.history(
                alice, dialog.toString(), before = message.id.toString(), after = message.id.toString(), limit = 50
            )
        }
        assertEquals(400, ex.statusCode)
    }

    @Test
    fun `hides a dialog the caller is not in behind a 404`() {
        // 404 rather than 403: a 403 would confirm that a guessed dialog id names a real conversation.
        val dialog = dialogOf(userId("alice"), userId("bob"))

        val ex = assertFailsWith<RelayException> {
            historyService.history(userId("mallory"), dialog.toString(), null, null, 50)
        }
        assertEquals(404, ex.statusCode)
    }

    @Test
    fun `refuses a cursor naming a message in another dialog`() {
        val alice = userId("alice")
        val ours = dialogOf(alice, userId("bob"))
        val theirs = dialogOf(alice, userId("carol"))
        val elsewhere = insert(theirs, alice, "other conversation", base)

        val ex = assertFailsWith<RelayException> {
            historyService.history(alice, ours.toString(), before = elsewhere.id.toString(), after = null, limit = 50)
        }
        assertEquals(400, ex.statusCode)
    }

    @Test
    fun `rejects a malformed dialog id as a bad request, not a server error`() {
        val ex = assertFailsWith<RelayException> {
            historyService.history(userId("alice"), "not-a-uuid", null, null, 50)
        }
        assertEquals(400, ex.statusCode)
    }
}

package com.relay.message.service

import com.relay.common.dto.SendMessageRequest
import com.relay.common.event.GroupChangeTypes
import com.relay.common.event.KafkaTopics
import com.relay.common.exception.RelayException
import com.relay.message.PostgresTestcontainerConfig
import com.relay.message.model.dto.CreateGroupDialogRequest
import com.relay.message.model.dto.event.GroupDialogChanged
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.kafka.test.context.EmbeddedKafka

/**
 * Group dialogs: creation idempotency by client-supplied id, the member cap under the dialog row
 * lock, membership changes as system messages, and the join-point read seed. The concurrent-add
 * case is the one that matters — it is why the cap check runs under `FOR UPDATE` instead of being
 * a read-then-write.
 *
 * The cap is overridden to 5 so its edges are reachable without 50 inserts per test.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "relay.message.group-member-cap=5"
    ]
)
@Import(PostgresTestcontainerConfig::class, GroupDialogServiceIT.RecorderConfig::class)
@EmbeddedKafka(partitions = 1, topics = [KafkaTopics.MESSAGES_DELIVERY])
class GroupDialogServiceIT {

    @Autowired private lateinit var groupDialogService: GroupDialogService
    @Autowired private lateinit var dialogService: DialogService
    @Autowired private lateinit var dialogQueryService: DialogQueryService
    @Autowired private lateinit var messageService: MessageService
    @Autowired private lateinit var messageHistoryService: MessageHistoryService
    @Autowired private lateinit var recorder: Recorder

    /** Records the in-transaction domain events, which carry the fan-out list the gateway will see. */
    class Recorder {
        val events = CopyOnWriteArrayList<GroupDialogChanged>()

        @EventListener
        fun on(event: GroupDialogChanged) {
            events += event
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class RecorderConfig {
        @Bean
        fun recorder() = Recorder()
    }

    @BeforeTest
    fun clearRecorder() {
        recorder.events.clear()
    }

    private fun userId(role: String) = "$role-${UUID.randomUUID()}"

    private fun createGroup(
        owner: String,
        vararg members: String,
        dialogId: String = UUID.randomUUID().toString(),
        title: String = "team"
    ): GroupMutationResult =
        groupDialogService.create(owner, CreateGroupDialogRequest(dialogId, title, members.toSet()))

    private fun send(dialogId: UUID, senderId: String, text: String = "hi") =
        messageService.send(SendMessageRequest(UUID.randomUUID().toString(), dialogId.toString(), senderId, text))

    private fun history(callerId: String, dialogId: UUID) =
        messageHistoryService.history(callerId, dialogId.toString(), before = null, after = null, limit = null).messages

    @Test
    fun `creating a group stores owner title and members, and everyone starts read`() {
        val owner = userId("alice")
        val bob = userId("bob")
        val carol = userId("carol")

        val result = createGroup(owner, bob, carol, title = "  team  ")

        assertTrue(result.created)
        val group = dialogQueryService.metadata(owner, result.dialogId.toString())
        assertEquals("group", group.type)
        assertEquals("team", group.title, "the title is stored trimmed")
        assertEquals(owner, group.ownerId)
        assertEquals(setOf(owner, bob, carol), group.participantIds.toSet())
        assertNotNull(group.lastMessageAt, "the creation system message stamps lastMessageAt")

        val rows = history(owner, result.dialogId)
        assertEquals(listOf("group_created"), rows.map { it.kind })
        assertEquals(owner, rows.single().senderId)
        assertNull(rows.single().clientMsgId, "a system row's key is server-minted and withheld from everyone")

        for (member in listOf(owner, bob, carol)) {
            assertEquals(
                0, dialogQueryService.metadata(member, result.dialogId.toString()).unreadCount,
                "founding members are seeded at the creation message"
            )
        }
    }

    @Test
    fun `a retried create returns the stored group instead of a twin`() {
        val owner = userId("alice")
        val dialogId = UUID.randomUUID().toString()
        val request = CreateGroupDialogRequest(dialogId, "team", setOf(userId("bob")))

        val first = groupDialogService.create(owner, request)
        val second = groupDialogService.create(owner, request)

        assertTrue(first.created)
        assertTrue(!second.created, "a replay converges, it does not duplicate")
        assertEquals(first.dialogId, second.dialogId)
        assertEquals(1, history(owner, first.dialogId).size, "no second system message either")
    }

    @Test
    fun `an id already taken by somebody else is a 409, not a takeover`() {
        val dialogId = UUID.randomUUID().toString()
        createGroup(userId("alice"), userId("bob"), dialogId = dialogId)

        val ex = assertFailsWith<RelayException> {
            createGroup(userId("mallory"), userId("eve"), dialogId = dialogId)
        }

        assertEquals(409, ex.statusCode)
    }

    @Test
    fun `refuses a group over the cap at creation`() {
        val ex = assertFailsWith<RelayException> {
            createGroup(userId("alice"), *(1..5).map { userId("m$it") }.toTypedArray())
        }
        assertEquals(400, ex.statusCode)
    }

    @Test
    fun `refuses an add that would exceed the cap`() {
        val owner = userId("alice")
        val group = createGroup(owner, *(1..4).map { userId("m$it") }.toTypedArray())

        val ex = assertFailsWith<RelayException> {
            groupDialogService.addMembers(owner, group.dialogId.toString(), setOf(userId("late")))
        }

        assertEquals(409, ex.statusCode)
    }

    /**
     * Both adds pass an unlocked read of the membership; the row lock is what makes the second one
     * see the first one's insert and refuse. If this flakes towards two successes, the cap has
     * regressed to a read-then-write.
     */
    @Test
    fun `two concurrent adds cannot slip past the cap together`() {
        val owner = userId("alice")
        val group = createGroup(owner, userId("m1"), userId("m2"), userId("m3"))
        val startTogether = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)

        val outcomes = try {
            (1..2).map {
                pool.submit<Boolean> {
                    startTogether.await()
                    try {
                        groupDialogService.addMembers(owner, group.dialogId.toString(), setOf(userId("late$it")))
                        true
                    } catch (ex: RelayException) {
                        assertEquals(409, ex.statusCode)
                        false
                    }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, outcomes.count { it }, "the lock admits exactly one of two adds at 4 of 5")
        assertEquals(
            5, dialogQueryService.metadata(owner, group.dialogId.toString()).participantIds.size,
            "membership sits exactly at the cap"
        )
    }

    @Test
    fun `adding an existing member is a silent no-op`() {
        val owner = userId("alice")
        val bob = userId("bob")
        val group = createGroup(owner, bob)
        val eventsBefore = recorder.events.size

        groupDialogService.addMembers(owner, group.dialogId.toString(), setOf(bob))

        assertEquals(eventsBefore, recorder.events.size, "nothing changed, nothing announced")
        assertEquals(1, history(owner, group.dialogId).size, "and no system message written")
    }

    @Test
    fun `a new member sees full history and starts unread at the join point`() {
        val owner = userId("alice")
        val bob = userId("bob")
        val carol = userId("carol")
        val group = createGroup(owner, bob)
        repeat(3) { send(group.dialogId, owner, "pre-join $it") }

        groupDialogService.addMembers(owner, group.dialogId.toString(), setOf(carol))

        assertEquals(
            0, dialogQueryService.metadata(carol, group.dialogId.toString()).unreadCount,
            "the seed puts the cursor at the member_added message"
        )
        val kinds = history(carol, group.dialogId).map { it.kind }
        assertEquals(
            listOf("member_added", "user", "user", "user", "group_created"), kinds,
            "everything before the join is readable"
        )

        send(group.dialogId, owner, "post-join")
        assertEquals(1, dialogQueryService.metadata(carol, group.dialogId.toString()).unreadCount)
    }

    @Test
    fun `removing a member drops their cursor and announces to them too`() {
        val owner = userId("alice")
        val bob = userId("bob")
        val carol = userId("carol")
        val group = createGroup(owner, bob, carol)

        groupDialogService.removeMember(owner, group.dialogId.toString(), bob)

        val remaining = dialogQueryService.metadata(owner, group.dialogId.toString())
        assertEquals(setOf(owner, carol), remaining.participantIds.toSet())
        assertTrue(
            dialogQueryService.readState(owner, group.dialogId.toString()).entries.none { it.userId == bob },
            "the removed member's cursor goes with them"
        )

        val row = history(owner, group.dialogId).first()
        assertEquals("member_removed", row.kind)
        assertEquals(bob, row.targetUserId)

        val event = recorder.events.last()
        assertEquals(GroupChangeTypes.MEMBER_REMOVED, event.change)
        assertTrue(bob in event.recipientIds, "the removed member needs the frame that says they are out")
    }

    @Test
    fun `only the owner manages the group and outsiders see nothing at all`() {
        val owner = userId("alice")
        val bob = userId("bob")
        val group = createGroup(owner, bob)
        val id = group.dialogId.toString()

        assertEquals(403, assertFailsWith<RelayException> {
            groupDialogService.rename(bob, id, "coup")
        }.statusCode)
        assertEquals(403, assertFailsWith<RelayException> {
            groupDialogService.addMembers(bob, id, setOf(userId("eve")))
        }.statusCode)
        assertEquals(403, assertFailsWith<RelayException> {
            groupDialogService.delete(bob, id)
        }.statusCode)
        assertEquals(404, assertFailsWith<RelayException> {
            groupDialogService.rename(userId("mallory"), id, "probe")
        }.statusCode, "an outsider gets the same answer as a dialog that does not exist")
        assertEquals(404, assertFailsWith<RelayException> {
            groupDialogService.removeMember(owner, id, userId("stranger"))
        }.statusCode)
        assertEquals(400, assertFailsWith<RelayException> {
            groupDialogService.removeMember(owner, id, owner)
        }.statusCode, "the owner leaves by deleting")
    }

    @Test
    fun `a group mutation aimed at a direct dialog is a 400`() {
        val alice = userId("alice")
        val direct = dialogService.openDirect(alice, userId("bob")).dialog.id

        val ex = assertFailsWith<RelayException> { groupDialogService.rename(alice, direct, "not a group") }

        assertEquals(400, ex.statusCode, "the caller holds the real id; the operation is the mistake")
    }

    @Test
    fun `a member can leave but the owner cannot`() {
        val owner = userId("alice")
        val bob = userId("bob")
        val group = createGroup(owner, bob)

        groupDialogService.leave(bob, group.dialogId.toString())

        assertEquals(
            setOf(owner), dialogQueryService.metadata(owner, group.dialogId.toString()).participantIds.toSet()
        )
        val row = history(owner, group.dialogId).first()
        assertEquals("member_left", row.kind)
        assertEquals(bob, row.targetUserId)
        assertEquals(bob, row.senderId, "leaving is something you do to yourself")

        assertEquals(422, assertFailsWith<RelayException> {
            groupDialogService.leave(owner, group.dialogId.toString())
        }.statusCode)
    }

    @Test
    fun `rename stores the title and a system message carrying it, a repeat announces nothing`() {
        val owner = userId("alice")
        val group = createGroup(owner, userId("bob"))

        groupDialogService.rename(owner, group.dialogId.toString(), "renamed")

        val metadata = dialogQueryService.metadata(owner, group.dialogId.toString())
        assertEquals("renamed", metadata.title)
        val row = history(owner, group.dialogId).first()
        assertEquals("group_renamed", row.kind)
        assertEquals("renamed", row.text, "the new title rides the system message so clients render without a refetch")

        val eventsBefore = recorder.events.size
        groupDialogService.rename(owner, group.dialogId.toString(), "renamed")
        assertEquals(eventsBefore, recorder.events.size, "an unchanged title is a no-op, not an announcement")
    }

    @Test
    fun `delete removes the group with its messages and cursors and announces to everyone it had`() {
        val owner = userId("alice")
        val bob = userId("bob")
        val group = createGroup(owner, bob)
        send(group.dialogId, owner)

        groupDialogService.delete(owner, group.dialogId.toString())

        assertEquals(404, assertFailsWith<RelayException> {
            dialogQueryService.metadata(owner, group.dialogId.toString())
        }.statusCode)
        val event = recorder.events.last()
        assertEquals(GroupChangeTypes.GROUP_DELETED, event.change)
        assertEquals(setOf(owner, bob), event.recipientIds, "announced to the membership the group had")
        assertNull(event.message, "nothing to anchor to — the messages are gone")
    }
}

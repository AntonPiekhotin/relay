package com.relay.call.service

import com.auth0.jwt.JWT
import com.relay.call.PostgresTestcontainerConfig
import com.relay.call.model.dto.CreateGroupCallRequest
import com.relay.call.repository.ActiveCallRepository
import com.relay.call.service.sfu.RoomDirectory
import com.relay.common.dto.AcceptCallRequest
import com.relay.common.dto.InviteCallRequest
import com.relay.common.event.CallSignalEvent
import com.relay.common.event.CallSignalKeys
import com.relay.common.event.CallSignalVerbs
import com.relay.common.event.KafkaTopics
import com.relay.common.event.NotificationRequestedEvent
import com.relay.common.exception.RelayException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import tools.jackson.databind.json.JsonMapper

/**
 * Covers what the group-call design rests on: that busy is claimed at join rather than at invite
 * (one busy invitee cannot kill the call, and a ringing invitee is not busy), that the last one out
 * ends the call exactly once, that the SFU's account of a vanished participant lands as a leave,
 * and that a group call and a direct call refuse each other's verbs.
 *
 * Same harness as [CallServiceIT]: `ring-timeout: 0s` makes rings expirable on demand, the sweeps
 * are driven by hand, and participants are named per test because `active_calls` is keyed by user
 * id. The SFU is a [StubRoomDirectory] — token minting is offline JWT signing and needs no stub.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "relay.call.ring-timeout=0s",
        "relay.call.sweep-interval=1h",
        "relay.call.reconcile-interval=1h",
        "relay.call.reconcile-grace=0s",
        "relay.call.group.max-participants=4"
    ]
)
@Import(PostgresTestcontainerConfig::class, GroupCallServiceIT.StubSfuConfig::class)
@EmbeddedKafka(partitions = 1, topics = [KafkaTopics.CALL_SIGNAL, KafkaTopics.NOTIFICATIONS])
class GroupCallServiceIT {

    class StubRoomDirectory : RoomDirectory {
        val rooms = ConcurrentHashMap<String, MutableSet<String>>()
        val closedRooms = CopyOnWriteArrayList<String>()
        override fun participantIdentities(room: String): Set<String>? = rooms[room]?.toSet()
        override fun closeRoom(room: String) {
            closedRooms += room
        }
    }

    @TestConfiguration
    class StubSfuConfig {
        @Bean
        @Primary
        fun stubRoomDirectory(): StubRoomDirectory = StubRoomDirectory()
    }

    @Autowired private lateinit var groupCallService: GroupCallService
    @Autowired private lateinit var callService: CallService
    @Autowired private lateinit var activeCallRepository: ActiveCallRepository
    @Autowired private lateinit var roomDirectory: StubRoomDirectory
    @Autowired private lateinit var broker: EmbeddedKafkaBroker
    @Autowired private lateinit var jsonMapper: JsonMapper

    private lateinit var consumer: Consumer<String, String>

    @BeforeTest
    fun subscribe() {
        val props = KafkaTestUtils.consumerProps(broker, "group-call-it-${UUID.randomUUID()}", true)
            .toMutableMap()
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        consumer = DefaultKafkaConsumerFactory(props, StringDeserializer(), StringDeserializer())
            .createConsumer()
        consumer.subscribe(listOf(KafkaTopics.CALL_SIGNAL, KafkaTopics.NOTIFICATIONS))
        // Poll until partitions are actually assigned, or the first test's drain races the
        // rebalance and reads nothing while records sit unfetched at offset zero.
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (consumer.assignment().isEmpty() && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(100))
        }
    }

    @AfterTest
    fun closeConsumer() {
        consumer.close()
    }

    // ---- helpers ----

    private fun create(
        caller: String,
        vararg invitees: String,
        callId: String = UUID.randomUUID().toString(),
        media: String = "audio",
        sessionId: String? = "sess-$caller"
    ) = groupCallService.create(
        caller,
        CreateGroupCallRequest(callId = callId, media = media, inviteeIds = invitees.toList(), sessionId = sessionId)
    )

    private fun drain(): Pair<List<CallSignalEvent>, List<NotificationRequestedEvent>> {
        val signals = mutableListOf<CallSignalEvent>()
        val pushes = mutableListOf<NotificationRequestedEvent>()
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        var quietPolls = 0
        while (quietPolls < 2 && System.nanoTime() < deadline) {
            val records = consumer.poll(Duration.ofMillis(300))
            if (records.isEmpty) {
                quietPolls++
                continue
            }
            quietPolls = 0
            records.records(KafkaTopics.CALL_SIGNAL)
                .forEach { signals += jsonMapper.readValue(it.value(), CallSignalEvent::class.java) }
            records.records(KafkaTopics.NOTIFICATIONS)
                .forEach { pushes += jsonMapper.readValue(it.value(), NotificationRequestedEvent::class.java) }
        }
        return signals to pushes
    }

    private fun signals(): List<CallSignalEvent> = drain().first

    private fun List<CallSignalEvent>.withVerb(verb: String): List<CallSignalEvent> =
        filter { it.signal[CallSignalKeys.VERB] == verb }

    private fun List<CallSignalEvent>.single(verb: String): CallSignalEvent =
        withVerb(verb).also { assertEquals(1, it.size, "expected exactly one '$verb' signal, got $it") }.first()

    private fun busy(userId: String): Boolean = activeCallRepository.findById(userId).isPresent

    // ---- create ----

    @Test
    fun `creating a group call rings every invitee and claims only the initiator`() {
        val result = create("gc-alice", "gc-bob", "gc-carol", media = "video")
        assertTrue(result.created)
        val call = result.response

        assertEquals("ringing", call.status)
        assertEquals("group", call.kind)
        assertEquals("gc-alice", call.initiator)
        assertEquals(
            mapOf("gc-alice" to "joined", "gc-bob" to "invited", "gc-carol" to "invited"),
            call.participants.associate { it.userId to it.state }
        )
        assertTrue(busy("gc-alice"), "the initiator is on the call they started")
        assertFalse(busy("gc-bob"), "a ringing invitee is not busy")
        assertFalse(busy("gc-carol"))

        val livekit = assertNotNull(call.livekit, "create admits the initiator to the room")
        assertEquals("gc-alice", JWT.decode(livekit.token).subject, "the token names the caller")

        val emitted = signals()
        val ring = emitted.single(CallSignalVerbs.GROUP_INVITE)
        assertEquals(setOf("gc-bob", "gc-carol"), ring.recipientIds.toSet(), "only invitees are rung")
        assertEquals("group", ring.signal[CallSignalKeys.KIND])
        assertEquals("video", ring.signal[CallSignalKeys.MEDIA])
        assertNotNull(ring.signal[CallSignalKeys.RING_EXPIRES_AT], "the gateway's push needs the deadline")
        assertNotNull(ring.signal[CallSignalKeys.PARTICIPANTS], "invitees see who else is on the roster")
        assertEquals(listOf("gc-alice"), emitted.single(CallSignalVerbs.STATE).recipientIds)
    }

    @Test
    fun `retrying a create is the same call re-rung, not a second call`() {
        val callId = UUID.randomUUID().toString()
        create("gcr-alice", "gcr-bob", callId = callId)
        drain()

        val retry = create("gcr-alice", "gcr-bob", callId = callId)
        assertFalse(retry.created)
        assertNotNull(retry.response.livekit, "the initiator is joined, so the retry refreshes the token")
        assertEquals(listOf("gcr-bob"), signals().single(CallSignalVerbs.GROUP_INVITE).recipientIds)
    }

    @Test
    fun `a busy creator is refused by the database`() {
        create("gbc-alice", "gbc-bob")
        val ex = assertFailsWith<RelayException> { create("gbc-alice", "gbc-carol") }
        assertEquals(409, ex.statusCode)
    }

    @Test
    fun `inviting yourself or too many people is refused`() {
        assertEquals(
            400,
            assertFailsWith<RelayException> { create("gv-alice", "gv-alice", "gv-bob") }.statusCode
        )
        // max-participants is 4 in this context: 4 invitees + the initiator is one too many.
        assertEquals(
            400,
            assertFailsWith<RelayException> { create("gv-alice", "gv-b", "gv-c", "gv-d", "gv-e") }.statusCode
        )
    }

    // ---- join ----

    @Test
    fun `the first join answers the call and tells everyone else`() {
        val callId = create("gj-alice", "gj-bob", "gj-carol").response.callId
        drain()

        val joined = groupCallService.join("gj-bob", callId, "sess-gj-bob-phone")
        assertEquals("answered", joined.status)
        assertNotNull(joined.livekit)
        assertEquals("gj-bob", JWT.decode(joined.livekit!!.token).subject)
        assertTrue(busy("gj-bob"), "joining is what claims the busy row")

        val emitted = signals()
        val delta = emitted.single(CallSignalVerbs.PARTICIPANT_JOINED)
        assertEquals("gj-bob", delta.signal[CallSignalKeys.USER_ID])
        assertEquals(setOf("gj-alice", "gj-carol"), delta.recipientIds.toSet())
        val cancel = emitted.single(CallSignalVerbs.CANCEL)
        assertEquals(listOf("gj-bob"), cancel.recipientIds, "only bob's own other devices stop ringing")
        assertEquals(listOf("sess-gj-bob-phone"), cancel.excludeSessionIds, "the device that joined is not told to")
    }

    @Test
    fun `joining again is a token refresh, not a transition`() {
        val callId = create("gjj-alice", "gjj-bob").response.callId
        groupCallService.join("gjj-bob", callId, null)
        drain()

        val again = groupCallService.join("gjj-bob", callId, null)
        assertEquals("answered", again.status)
        assertNotNull(again.livekit, "a reconnecting client gets a fresh token")
        assertTrue(signals().isEmpty(), "an idempotent re-join tells nobody anything")
    }

    @Test
    fun `a stranger cannot join and a participant in another call cannot either`() {
        val callId = create("gs-alice", "gs-bob").response.callId
        assertEquals(
            403,
            assertFailsWith<RelayException> { groupCallService.join("gs-mallory", callId, null) }.statusCode
        )

        // Bob answers a different call first; the primary key, not a query, refuses the second.
        val otherCallId = create("gs-dave", "gs-bob").response.callId
        groupCallService.join("gs-bob", otherCallId, null)
        assertEquals(
            409,
            assertFailsWith<RelayException> { groupCallService.join("gs-bob", callId, null) }.statusCode
        )
    }

    @Test
    fun `joining an ended call is refused as too late`() {
        val callId = create("gjt-alice", "gjt-bob").response.callId
        groupCallService.leave("gjt-alice", callId, null) // initiator abandons the ring
        assertEquals(
            422,
            assertFailsWith<RelayException> { groupCallService.join("gjt-bob", callId, null) }.statusCode
        )
    }

    // ---- decline ----

    @Test
    fun `a decline is a roster delta and declining is not becoming busy`() {
        val callId = create("gd-alice", "gd-bob", "gd-carol").response.callId
        drain()

        val declined = groupCallService.decline("gd-bob", callId, "busy elsewhere", "sess-gd-bob")
        assertEquals("ringing", declined.status, "one refusal does not end a call others may still take")
        assertFalse(busy("gd-bob"))

        val emitted = signals()
        val delta = emitted.single(CallSignalVerbs.PARTICIPANT_DECLINED)
        assertEquals("gd-bob", delta.signal[CallSignalKeys.USER_ID])
        assertEquals(setOf("gd-alice", "gd-carol"), delta.recipientIds.toSet())
        emitted.single(CallSignalVerbs.CANCEL)
    }

    @Test
    fun `a decliner may change their mind while the call lives`() {
        val callId = create("gdc-alice", "gdc-bob", "gdc-carol").response.callId
        groupCallService.decline("gdc-bob", callId, null, null)

        val joined = groupCallService.join("gdc-bob", callId, null)
        assertEquals("joined", joined.participants.first { it.userId == "gdc-bob" }.state)
    }

    @Test
    fun `the last refusal ends a call nobody joined`() {
        val callId = create("gld-alice", "gld-bob", "gld-carol").response.callId
        drain()
        groupCallService.decline("gld-bob", callId, null, null)

        val ended = groupCallService.decline("gld-carol", callId, null, null)
        assertEquals("rejected", ended.status)
        assertEquals("all_declined", ended.endReason)
        assertFalse(busy("gld-alice"), "the initiator is freed the moment the call dies")

        val endedSignal = signals().single(CallSignalVerbs.GROUP_ENDED)
        assertEquals(setOf("gld-alice", "gld-bob", "gld-carol"), endedSignal.recipientIds.toSet())
        assertTrue(callId in roomDirectory.closedRooms, "the SFU room is closed after the commit")
    }

    // ---- leave ----

    @Test
    fun `a non-last leave frees the leaver and the call goes on`() {
        val callId = create("gl-alice", "gl-bob", "gl-carol").response.callId
        groupCallService.join("gl-bob", callId, null)
        groupCallService.join("gl-carol", callId, null)
        drain()

        val left = groupCallService.leave("gl-bob", callId, null)
        assertEquals("answered", left.status)
        assertFalse(busy("gl-bob"))
        assertTrue(busy("gl-carol"))

        val delta = signals().single(CallSignalVerbs.PARTICIPANT_LEFT)
        assertEquals("gl-bob", delta.signal[CallSignalKeys.USER_ID])
        assertEquals(setOf("gl-alice", "gl-carol"), delta.recipientIds.toSet())
    }

    @Test
    fun `the last one out ends the call exactly once`() {
        val callId = create("gll-alice", "gll-bob").response.callId
        groupCallService.join("gll-bob", callId, null)
        groupCallService.leave("gll-alice", callId, null)
        drain()

        val ended = groupCallService.leave("gll-bob", callId, null)
        assertEquals("ended", ended.status)
        assertEquals("all_left", ended.endReason)
        assertNotNull(ended.durationSeconds, "an answered call has talk time")
        assertFalse(busy("gll-alice"))
        assertFalse(busy("gll-bob"))
        signals().single(CallSignalVerbs.GROUP_ENDED)

        // And leaving again changes nothing — the webhook may say it a second time.
        val repeat = groupCallService.leave("gll-bob", callId, null)
        assertEquals("ended", repeat.status)
        assertTrue(signals().isEmpty())
    }

    @Test
    fun `the initiator abandoning the ring cancels the whole call`() {
        val callId = create("gab-alice", "gab-bob", "gab-carol").response.callId
        drain()

        val ended = groupCallService.leave("gab-alice", callId, null)
        assertEquals("ended", ended.status)
        assertEquals("caller_canceled", ended.endReason)
        assertNull(ended.durationSeconds, "never answered, so no talk time")

        val endedSignal = signals().single(CallSignalVerbs.GROUP_ENDED)
        assertEquals(setOf("gab-alice", "gab-bob", "gab-carol"), endedSignal.recipientIds.toSet())
    }

    @Test
    fun `a participant may rejoin an ongoing call after leaving`() {
        val callId = create("grj-alice", "grj-bob").response.callId
        groupCallService.join("grj-bob", callId, null)
        groupCallService.leave("grj-bob", callId, null)

        val rejoined = groupCallService.join("grj-bob", callId, null)
        assertEquals("answered", rejoined.status)
        assertNotNull(rejoined.livekit)
        assertTrue(busy("grj-bob"))
    }

    // ---- the two kinds refuse each other's verbs ----

    @Test
    fun `group endpoints refuse a direct call and direct verbs refuse a group call`() {
        val directId = UUID.randomUUID().toString()
        callService.invite(
            InviteCallRequest(
                callId = directId, callerId = "gx-dave", calleeId = "gx-erin",
                sessionId = "sess-gx-dave", media = "audio", sdp = "v=0"
            )
        )
        assertEquals(
            400,
            assertFailsWith<RelayException> { groupCallService.join("gx-erin", directId, null) }.statusCode
        )

        val groupId = create("gx-alice", "gx-bob").response.callId
        assertEquals(
            400,
            assertFailsWith<RelayException> {
                callService.accept(groupId, AcceptCallRequest(userId = "gx-bob", sessionId = "s", sdp = "v=0"))
            }.statusCode
        )
    }

    // ---- ring timeout ----

    @Test
    fun `a group call nobody joins is missed, with a push per invitee`() {
        val callId = create("gm-alice", "gm-bob", "gm-carol").response.callId
        drain()

        val rungOut = groupCallService.findRungOutGroupCallIds()
        assertTrue(UUID.fromString(callId) in rungOut)
        assertTrue(groupCallService.expireRungOutGroupCall(UUID.fromString(callId)))
        assertFalse(busy("gm-alice"))

        val (emitted, pushes) = drain()
        val endedSignal = emitted.single(CallSignalVerbs.GROUP_ENDED)
        assertEquals("ring_timeout", endedSignal.signal[CallSignalKeys.REASON])
        assertEquals(setOf("gm-alice", "gm-bob", "gm-carol"), endedSignal.recipientIds.toSet())

        assertEquals(setOf("gm-bob", "gm-carol"), pushes.map { it.recipientId }.toSet())
        pushes.forEach {
            assertEquals(NotificationRequestedEvent.KIND_MISSED_CALL, it.kind)
            assertEquals("group", it.payload[NotificationRequestedEvent.KEY_CALL_KIND])
        }
    }

    @Test
    fun `an unanswered invitee rings out individually while the call goes on`() {
        val callId = create("gpi-alice", "gpi-bob", "gpi-carol").response.callId
        groupCallService.join("gpi-bob", callId, null)
        drain()

        val pending = groupCallService.findGroupCallIdsWithPendingInvites()
        assertTrue(UUID.fromString(callId) in pending)
        assertEquals(1, groupCallService.expirePendingInvites(UUID.fromString(callId)))

        val described = groupCallService.describe("gpi-alice", callId)
        assertEquals("answered", described.status, "the call outlives the invitee's ring")
        assertEquals("missed", described.participants.first { it.userId == "gpi-carol" }.state)

        val (emitted, pushes) = drain()
        val delta = emitted.single(CallSignalVerbs.PARTICIPANT_MISSED)
        assertEquals("gpi-carol", delta.signal[CallSignalKeys.USER_ID])
        assertEquals(
            setOf("gpi-alice", "gpi-bob", "gpi-carol"), delta.recipientIds.toSet(),
            "the rung-out invitee's own devices stop ringing on the same delta"
        )
        assertEquals(listOf("gpi-carol"), pushes.map { it.recipientId })
    }

    // ---- the SFU's account: webhooks and reconciliation ----

    @Test
    fun `the SFU reporting a participant gone lands as a leave`() {
        val callId = create("gw-alice", "gw-bob", "gw-carol").response.callId
        groupCallService.join("gw-bob", callId, null)
        groupCallService.join("gw-carol", callId, null)
        drain()

        groupCallService.onSfuParticipantLeft(UUID.fromString(callId), "gw-bob")
        assertFalse(busy("gw-bob"))
        val delta = signals().single(CallSignalVerbs.PARTICIPANT_LEFT)
        assertEquals("disconnected", delta.signal[CallSignalKeys.REASON])

        // The webhook arriving twice — or after the client's own REST leave — is a no-op.
        groupCallService.onSfuParticipantLeft(UUID.fromString(callId), "gw-bob")
        assertTrue(signals().isEmpty())
    }

    @Test
    fun `the SFU finishing the room ends the call`() {
        val callId = create("gwf-alice", "gwf-bob").response.callId
        groupCallService.join("gwf-bob", callId, null)
        drain()

        groupCallService.onSfuRoomFinished(UUID.fromString(callId))
        assertEquals("ended", groupCallService.describe("gwf-alice", callId).status)
        assertFalse(busy("gwf-alice"))
        assertFalse(busy("gwf-bob"))
        signals().single(CallSignalVerbs.GROUP_ENDED)

        groupCallService.onSfuRoomFinished(UUID.fromString(callId))
        assertTrue(signals().isEmpty(), "finishing an already finished room changes nothing")
    }

    @Test
    fun `reconciliation removes a participant the SFU does not know and ends an empty call`() {
        val callId = create("grc-alice", "grc-bob", "grc-carol").response.callId
        groupCallService.join("grc-bob", callId, null)
        groupCallService.join("grc-carol", callId, null)
        drain()

        // The SFU only ever saw alice and carol — bob's token was minted but never used, or his
        // participant_left webhook was lost. Either way he must not stay busy forever.
        var removed = groupCallService.reconcile(UUID.fromString(callId), setOf("grc-alice", "grc-carol"))
        assertEquals(1, removed)
        assertFalse(busy("grc-bob"))
        assertEquals("answered", groupCallService.describe("grc-alice", callId).status)
        drain()

        // Now the room is empty: everyone left is vanished, and the call must end, once.
        removed = groupCallService.reconcile(UUID.fromString(callId), emptySet())
        assertEquals(2, removed)
        val described = groupCallService.describe("grc-alice", callId)
        assertEquals("ended", described.status)
        assertEquals("all_left", described.endReason)
        signals().single(CallSignalVerbs.GROUP_ENDED)
    }

    // ---- reading ----

    @Test
    fun `describing a call never mints a token and is invisible to strangers`() {
        val callId = create("gr-alice", "gr-bob").response.callId
        assertNull(groupCallService.describe("gr-bob", callId).livekit)
        assertEquals(
            403,
            assertFailsWith<RelayException> { groupCallService.describe("gr-mallory", callId) }.statusCode
        )
    }
}

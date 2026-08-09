package com.relay.call.service

import com.relay.call.PostgresTestcontainerConfig
import com.relay.call.repository.ActiveCallRepository
import com.relay.common.dto.AcceptCallRequest
import com.relay.common.dto.HangupCallRequest
import com.relay.common.dto.IceCandidateRequest
import com.relay.common.dto.InviteCallRequest
import com.relay.common.dto.RejectCallRequest
import com.relay.common.event.CallSignalEvent
import com.relay.common.event.CallSignalKeys
import com.relay.common.event.CallSignalVerbs
import com.relay.common.event.KafkaTopics
import com.relay.common.event.NotificationRequestedEvent
import com.relay.common.exception.RelayException
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import tools.jackson.databind.json.JsonMapper

/**
 * Covers the invariants the call design rests on: that a busy participant is refused by the database
 * rather than by a query, that only the callee can answer and only once, that a device which just
 * answered is not told to cancel, that talk time is not ring time, and that the server — not a
 * client — decides a call was missed.
 *
 * `ring-timeout: 0s` makes every ringing call immediately expirable, so the sweep can be driven by
 * hand instead of by waiting; `sweep-interval: 1h` keeps the scheduled sweep from firing mid-test
 * (its one automatic run happens at startup, before any call exists).
 *
 * Participants are named per test. `active_calls` is keyed by user id and survives a ringing call, so
 * sharing "alice" across tests would make them refuse each other.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "relay.call.ring-timeout=0s",
        "relay.call.sweep-interval=1h"
    ]
)
@Import(PostgresTestcontainerConfig::class)
@EmbeddedKafka(partitions = 1, topics = [KafkaTopics.CALL_SIGNAL, KafkaTopics.NOTIFICATIONS])
class CallServiceIT {

    @Autowired private lateinit var callService: CallService
    @Autowired private lateinit var sweeper: CallSweeper
    @Autowired private lateinit var iceBuffer: IceCandidateBuffer
    @Autowired private lateinit var activeCallRepository: ActiveCallRepository
    @Autowired private lateinit var broker: EmbeddedKafkaBroker
    @Autowired private lateinit var jsonMapper: JsonMapper

    private lateinit var consumer: Consumer<String, String>

    @BeforeTest
    fun subscribe() {
        val props = KafkaTestUtils.consumerProps(broker, "call-it-${UUID.randomUUID()}", true)
            .toMutableMap()
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        consumer = DefaultKafkaConsumerFactory(props, StringDeserializer(), StringDeserializer())
            .createConsumer()
        consumer.subscribe(listOf(KafkaTopics.CALL_SIGNAL, KafkaTopics.NOTIFICATIONS))
        consumer.poll(Duration.ofMillis(200))
    }

    @AfterTest
    fun closeConsumer() {
        consumer.close()
    }

    // ---- helpers ----

    private fun invite(
        caller: String,
        callee: String,
        callId: String = UUID.randomUUID().toString(),
        sessionId: String = "sess-$caller",
        media: String = "audio",
        sdp: String = "v=0\r\no=- offer"
    ) = callService.invite(
        InviteCallRequest(
            callId = callId,
            callerId = caller,
            calleeId = callee,
            sessionId = sessionId,
            media = media,
            sdp = sdp
        )
    )

    /**
     * Everything published since the last drain.
     *
     * Polls until two consecutive polls come back empty rather than stopping at the first record.
     * One operation emits several signals — an accept produces both an answer and a cancel — and a
     * drain that returns after the first one leaves the rest to surface inside the *next*
     * assertion, which reads as a phantom signal for an operation that did not emit it.
     */
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

    // ---- invite ----

    @Test
    fun `an invite rings the callee and tells the caller it is ringing`() {
        val call = invite(caller = "inv-alice", callee = "inv-bob", media = "video", sdp = "v=0 my-offer")

        assertEquals("ringing", call.status)
        assertEquals("video", call.media)
        assertEquals("inv-alice", call.initiator)
        assertEquals(setOf("inv-alice", "inv-bob"), call.participantIds.toSet())

        val emitted = signals()
        val ring = emitted.single(CallSignalVerbs.INVITE)
        assertEquals(listOf("inv-bob"), ring.recipientIds, "only the callee is rung")
        assertEquals("inv-alice", ring.fromUserId)
        assertEquals("v=0 my-offer", ring.signal[CallSignalKeys.SDP], "the offer is relayed verbatim")
        assertNotNull(ring.signal[CallSignalKeys.RING_EXPIRES_AT], "clients need the ring deadline")

        val state = emitted.single(CallSignalVerbs.STATE)
        assertEquals(listOf("inv-alice"), state.recipientIds, "the caller's own devices learn it is ringing")
        assertEquals("ringing", state.signal[CallSignalKeys.STATUS])
    }

    @Test
    fun `a repeated call id is the same call, re-rung rather than duplicated`() {
        val callId = UUID.randomUUID().toString()

        val first = invite(caller = "rpt-alice", callee = "rpt-bob", callId = callId)
        val second = invite(caller = "rpt-alice", callee = "rpt-bob", callId = callId)

        assertEquals(first.id, second.id)
        assertEquals(first.startedAt, second.startedAt, "the retry must not restart the call")
        assertEquals(
            2,
            signals().withVerb(CallSignalVerbs.INVITE).size,
            "a client retries because it saw no confirmation — re-ring rather than go silent"
        )
    }

    @Test
    fun `a caller cannot call themselves`() {
        val ex = assertFailsWith<RelayException> { invite(caller = "solo", callee = "solo") }
        assertEquals(400, ex.statusCode)
    }

    @Test
    fun `inviting someone already in a call is refused by the database, not by a query`() {
        invite(caller = "busy-alice", callee = "busy-bob")

        val ex = assertFailsWith<RelayException> { invite(caller = "busy-carol", callee = "busy-bob") }

        assertEquals(409, ex.statusCode)
        assertTrue(activeCallRepository.findById("busy-carol").isEmpty, "the refused call claimed nobody")
    }

    @Test
    fun `two users dialling each other resolve to one call`() {
        invite(caller = "glare-alice", callee = "glare-bob")

        // The other half of glare: whichever transaction commits second collides on active_calls.
        val ex = assertFailsWith<RelayException> { invite(caller = "glare-bob", callee = "glare-alice") }

        assertEquals(409, ex.statusCode)
    }

    // ---- accept ----

    @Test
    fun `answering sends the answer to the caller and cancels the callee's other devices only`() {
        val call = invite(caller = "acc-alice", callee = "acc-bob")
        signals()

        val answered = callService.accept(
            call.id,
            AcceptCallRequest(userId = "acc-bob", sessionId = "phone", sdp = "v=0 my-answer")
        )

        assertEquals("answered", answered.status)
        assertNotNull(answered.answeredAt)

        val emitted = signals()
        val accept = emitted.single(CallSignalVerbs.ACCEPT)
        assertEquals(listOf("acc-alice"), accept.recipientIds)
        assertEquals("v=0 my-answer", accept.signal[CallSignalKeys.SDP])

        val cancel = emitted.single(CallSignalVerbs.CANCEL)
        assertEquals(listOf("acc-bob"), cancel.recipientIds, "only the callee's own devices stop ringing")
        assertEquals(
            listOf("phone"),
            cancel.excludeSessionIds,
            "the device that just answered must not be told to cancel"
        )
    }

    @Test
    fun `the caller cannot answer their own call`() {
        val call = invite(caller = "own-alice", callee = "own-bob")

        val ex = assertFailsWith<RelayException> {
            callService.accept(call.id, AcceptCallRequest("own-alice", "sess", "v=0 answer"))
        }
        assertEquals(403, ex.statusCode)
    }

    @Test
    fun `a stranger cannot answer a call they are not in`() {
        val call = invite(caller = "str-alice", callee = "str-bob")

        val ex = assertFailsWith<RelayException> {
            callService.accept(call.id, AcceptCallRequest("mallory", "sess", "v=0 answer"))
        }
        assertEquals(403, ex.statusCode)
    }

    @Test
    fun `a call can only be answered once`() {
        val call = invite(caller = "twice-alice", callee = "twice-bob")
        callService.accept(call.id, AcceptCallRequest("twice-bob", "phone", "v=0 answer"))

        val ex = assertFailsWith<RelayException> {
            callService.accept(call.id, AcceptCallRequest("twice-bob", "tablet", "v=0 other-answer"))
        }
        assertEquals(422, ex.statusCode, "the second device is too late, not forbidden")
    }

    @Test
    fun `answering an unknown call is not found`() {
        val ex = assertFailsWith<RelayException> {
            callService.accept(UUID.randomUUID().toString(), AcceptCallRequest("nobody", "sess", "v=0"))
        }
        assertEquals(404, ex.statusCode)
    }

    // ---- reject and hangup ----

    @Test
    fun `rejecting frees both participants to be called again`() {
        val call = invite(caller = "rej-alice", callee = "rej-bob")
        signals()

        val rejected = callService.reject(call.id, RejectCallRequest("rej-bob", "phone", "busy_here"))

        assertEquals("rejected", rejected.status)
        assertEquals("busy_here", rejected.endReason)
        assertNull(rejected.durationSeconds, "a call that was never answered has no duration")
        assertTrue(activeCallRepository.findById("rej-alice").isEmpty)
        assertTrue(activeCallRepository.findById("rej-bob").isEmpty)

        val emitted = signals()
        assertEquals(listOf("rej-alice"), emitted.single(CallSignalVerbs.REJECT).recipientIds)
        assertEquals(listOf("rej-bob"), emitted.single(CallSignalVerbs.CANCEL).recipientIds)

        // Proof the release worked, not just that the rows went away.
        invite(caller = "rej-carol", callee = "rej-bob")
    }

    @Test
    fun `hanging up an answered call records talk time and not ring time`() {
        val call = invite(caller = "dur-alice", callee = "dur-bob")
        callService.accept(call.id, AcceptCallRequest("dur-bob", "phone", "v=0 answer"))
        signals()

        val ended = callService.hangup(call.id, HangupCallRequest("dur-alice", "sess-dur-alice"))

        assertEquals("ended", ended.status)
        assertEquals("hangup", ended.endReason)
        assertNotNull(ended.durationSeconds, "an answered call has a duration")
        assertTrue(ended.durationSeconds!! < 5, "duration is measured from the answer, not the invite")

        assertEquals(listOf("dur-bob"), signals().single(CallSignalVerbs.HANGUP).recipientIds)
    }

    @Test
    fun `a caller who gives up before an answer ends the call as cancelled`() {
        val call = invite(caller = "can-alice", callee = "can-bob")
        signals()

        val ended = callService.hangup(call.id, HangupCallRequest("can-alice", "sess-can-alice"))

        assertEquals("ended", ended.status)
        assertEquals("caller_canceled", ended.endReason)
        assertNull(ended.durationSeconds)
        assertEquals(listOf("can-bob"), signals().single(CallSignalVerbs.HANGUP).recipientIds)
    }

    @Test
    fun `both sides hanging up at once is a no-op the second time`() {
        val call = invite(caller = "dbl-alice", callee = "dbl-bob")
        callService.accept(call.id, AcceptCallRequest("dbl-bob", "phone", "v=0 answer"))
        val first = callService.hangup(call.id, HangupCallRequest("dbl-alice", "sess-a"))
        signals()

        val second = callService.hangup(call.id, HangupCallRequest("dbl-bob", "phone"))

        assertEquals(first.endedAt, second.endedAt, "the second hangup must not rewrite the outcome")
        assertTrue(signals().isEmpty(), "and must not signal anything")
    }

    // ---- ring timeout ----

    @Test
    fun `an unanswered call is missed by the server and pushed to the callee`() {
        val call = invite(caller = "mis-alice", callee = "mis-bob")
        signals()

        val expired = callService.findRungOutCallIds()
        assertTrue(UUID.fromString(call.id) in expired)
        assertTrue(callService.expireRungOutCall(UUID.fromString(call.id)))

        val (emitted, pushes) = drain()
        val missed = emitted.single(CallSignalVerbs.MISSED)
        assertEquals(
            setOf("mis-alice", "mis-bob"),
            missed.recipientIds.toSet(),
            "the caller stops its outgoing UI and the callee stops ringing"
        )
        assertEquals("ring_timeout", missed.signal[CallSignalKeys.REASON])

        val push = pushes.single()
        assertEquals(NotificationRequestedEvent.KIND_MISSED_CALL, push.kind)
        assertEquals("mis-bob", push.recipientId, "only the callee missed anything")
        assertEquals(call.id, push.payload[NotificationRequestedEvent.KEY_CALL_ID])

        assertTrue(activeCallRepository.findById("mis-alice").isEmpty)
        assertTrue(activeCallRepository.findById("mis-bob").isEmpty)
    }

    @Test
    fun `a call that was answered is not expired`() {
        val call = invite(caller = "keep-alice", callee = "keep-bob")
        callService.accept(call.id, AcceptCallRequest("keep-bob", "phone", "v=0 answer"))

        assertTrue(
            !callService.expireRungOutCall(UUID.fromString(call.id)),
            "the sweeper must lose to an answer, not overwrite it"
        )
    }

    // ---- ICE ----

    @Test
    fun `a candidate is relayed to the other participant only`() {
        val call = invite(caller = "ice-alice", callee = "ice-bob")
        signals()

        callService.relayIce(
            call.id,
            IceCandidateRequest("ice-alice", "sess-a", mapOf("candidate" to "candidate:1 udp"))
        )

        val ice = signals().single(CallSignalVerbs.ICE)
        assertEquals(listOf("ice-bob"), ice.recipientIds)
        @Suppress("UNCHECKED_CAST")
        val candidate = ice.signal[CallSignalKeys.CANDIDATE] as Map<String, Any?>
        assertEquals("candidate:1 udp", candidate["candidate"], "candidates are opaque and unmodified")
    }

    @Test
    fun `a candidate that outruns its invite is held and delivered once the call appears`() {
        val callId = UUID.randomUUID().toString()

        callService.relayIce(
            callId,
            IceCandidateRequest("early-alice", "sess-a", mapOf("candidate" to "candidate:early"))
        )
        assertTrue(signals().isEmpty(), "there is nobody to relay to yet")
        assertTrue(UUID.fromString(callId) in iceBuffer.pendingCallIds())

        invite(caller = "early-alice", callee = "early-bob", callId = callId)

        val emitted = signals()
        val ice = emitted.single(CallSignalVerbs.ICE)
        assertEquals(listOf("early-bob"), ice.recipientIds)
        assertTrue(
            emitted.indexOf(emitted.single(CallSignalVerbs.INVITE)) < emitted.indexOf(ice),
            "the peer needs the offer before its candidates"
        )
    }

    @Test
    fun `a buffered candidate from someone who turns out not to be a participant is discarded`() {
        val callId = UUID.randomUUID().toString()
        callService.relayIce(
            callId,
            IceCandidateRequest("mallory", "sess-m", mapOf("candidate" to "candidate:forged"))
        )
        signals()

        invite(caller = "fake-alice", callee = "fake-bob", callId = callId)

        assertTrue(
            signals().withVerb(CallSignalVerbs.ICE).isEmpty(),
            "buffering happens before the call exists, so membership is only checkable on release"
        )
    }

    @Test
    fun `a candidate for a finished call is dropped rather than buffered`() {
        val call = invite(caller = "late-alice", callee = "late-bob")
        callService.hangup(call.id, HangupCallRequest("late-alice", "sess-a"))
        signals()

        callService.relayIce(
            call.id,
            IceCandidateRequest("late-alice", "sess-a", mapOf("candidate" to "candidate:late"))
        )

        assertTrue(signals().isEmpty())
        assertTrue(UUID.fromString(call.id) !in iceBuffer.pendingCallIds(), "there is no peer left to wait for")
    }

    @Test
    fun `a stranger cannot relay candidates into a live call`() {
        val call = invite(caller = "guard-alice", callee = "guard-bob")

        val ex = assertFailsWith<RelayException> {
            callService.relayIce(call.id, IceCandidateRequest("mallory", "sess-m", mapOf("candidate" to "x")))
        }
        assertEquals(403, ex.statusCode)
    }

    @Test
    fun `the sweeper relays candidates whose call has since appeared`() {
        val callId = UUID.randomUUID().toString()
        invite(caller = "sw-alice", callee = "sw-bob", callId = callId)
        // Answered on purpose: this suite runs with a zero ring timeout, so a call left ringing
        // would be expired by the same sweep and its candidates dropped along with it.
        callService.accept(callId, AcceptCallRequest("sw-bob", "phone", "v=0 answer"))
        signals()

        // Buffered directly: this is the narrow window where a candidate lands after the invite
        // already flushed the buffer.
        iceBuffer.buffer(UUID.fromString(callId), "sw-alice", mapOf("candidate" to "candidate:raced"))
        sweeper.sweep()

        assertEquals(listOf("sw-bob"), signals().single(CallSignalVerbs.ICE).recipientIds)
    }

    // ---- history ----

    @Test
    fun `history is newest-first, per participant, and paginates by cursor`() {
        val first = invite(caller = "h-alice", callee = "h-bob")
        callService.hangup(first.id, HangupCallRequest("h-alice", "sess-a"))
        val second = invite(caller = "h-bob", callee = "h-alice")
        callService.hangup(second.id, HangupCallRequest("h-bob", "sess-b"))
        val third = invite(caller = "h-alice", callee = "h-bob")
        callService.hangup(third.id, HangupCallRequest("h-alice", "sess-a"))

        val page = callService.history("h-alice", before = null, limit = 2)

        assertEquals(listOf(third.id, second.id), page.calls.map { it.id }, "newest first")
        assertEquals("outgoing", page.calls.first().direction)
        assertEquals("h-bob", page.calls.first().peerId)
        assertEquals("incoming", page.calls.last().direction, "direction is relative to who is asking")
        assertEquals(second.id, page.nextCursor)

        val next = callService.history("h-alice", before = page.nextCursor, limit = 2)
        assertEquals(listOf(first.id), next.calls.map { it.id })
        assertNull(next.nextCursor, "the last page says so")
    }

    @Test
    fun `history shows nothing to somebody who was not in the call`() {
        val call = invite(caller = "priv-alice", callee = "priv-bob")
        callService.hangup(call.id, HangupCallRequest("priv-alice", "sess-a"))

        assertTrue(callService.history("priv-mallory", before = null, limit = 10).calls.isEmpty())
    }
}

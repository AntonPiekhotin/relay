package com.relay.websocket.presence

import com.relay.common.event.KafkaTopics
import com.relay.common.event.PresenceEvent
import com.relay.common.event.PresenceStatuses
import com.relay.common.event.TypingEvent
import com.relay.common.model.UserPrincipal
import com.relay.websocket.output.event.PresenceEventProducer
import com.relay.websocket.output.http.DialogMembershipResolver
import com.relay.websocket.output.socket.FrameDispatcher
import com.relay.websocket.protocol.ErrorCodes
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.protocol.PresenceStatus
import com.relay.websocket.session.InMemorySessionRegistry
import com.relay.websocket.session.RelaySession
import com.relay.websocket.util.MessageClientProperties
import com.relay.websocket.util.PresenceProperties
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.anyString
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * Presence has two halves and they meet at the broker: a node *observes* and publishes, every node
 * *consumes* and delivers. These tests drive both, and [drainBroker] stands in for the hop — which on
 * a single node is this same process reading back what it just wrote.
 *
 * A real [PresenceEventProducer] over a mocked template, matching `InboundFrameRouterTest`: it pins
 * the whole published contract — topic, partition key, and payload — not just that "something was
 * published". The keys are the part worth pinning; presence is keyed by user and typing by dialog, and
 * getting either wrong reorders exactly the events whose order matters.
 */
class PresenceServiceTest {

    private val registry = InMemorySessionRegistry()
    private val subscriptions = PresenceSubscriptions()
    private val lastSeen = LastSeenStore(PresenceProperties())
    private val membershipClient = StubDialogMembershipClient().withDialog("d-1", "alice", "bob")
    private val jsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Suppress("UNCHECKED_CAST")
    private val kafkaTemplate = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>

    private val presence = PresenceService(
        subscriptions = subscriptions,
        lastSeen = lastSeen,
        registry = registry,
        membership = DialogMembershipResolver(membershipClient, MessageClientProperties()),
        dispatcher = FrameDispatcher(registry),
        producer = PresenceEventProducer(kafkaTemplate, jsonMapper)
    )

    private var counter = 0

    @BeforeTest
    fun publishSucceeds() {
        `when`(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null as SendResult<String, String>?))
    }

    private fun session(userId: String): RelaySession =
        RelaySession("s-${counter++}", UserPrincipal(userId, null, emptySet()), 16)
            .also(registry::register)

    /** Stands in for the writer thread; whatever was dispatched is already queued, so this never parks. */
    private fun RelaySession.nextFrame(): OutboundFrame =
        assertIs<RelaySession.Outbound.Frame>(awaitOutbound()).frame

    private fun RelaySession.hasNoFrames() {
        complete()
        assertEquals(
            RelaySession.Outbound.Completed,
            awaitOutbound(),
            "the terminal marker arriving first proves nothing was queued ahead of it"
        )
    }

    // ---- the broker, stood in for ----

    private data class Sent(val topic: String, val key: String, val value: String)

    private var drained = 0

    private fun sent(): List<Sent> {
        val topic = ArgumentCaptor.forClass(String::class.java)
        val key = ArgumentCaptor.forClass(String::class.java)
        val value = ArgumentCaptor.forClass(String::class.java)
        verify(kafkaTemplate, atLeast(0)).send(topic.capture(), key.capture(), value.capture())
        return topic.allValues.indices.map { Sent(topic.allValues[it], key.allValues[it], value.allValues[it]) }
    }

    /**
     * Feeds everything published since the last call back into the consuming side, in publish order —
     * which is what a single node does with its own events, and what the partition key guarantees for
     * one user's or one dialog's events on many nodes.
     */
    private fun drainBroker() {
        val pending = sent().drop(drained)
        drained += pending.size
        pending.forEach { record ->
            when (record.topic) {
                KafkaTopics.PRESENCE_UPDATE ->
                    presence.deliver(jsonMapper.readValue(record.value, PresenceEvent::class.java))
                KafkaTopics.TYPING_START ->
                    presence.deliver(jsonMapper.readValue(record.value, TypingEvent::class.java))
                else -> error("unexpected topic ${record.topic}")
            }
        }
    }

    private fun publishedPresence(): List<Pair<String, PresenceEvent>> =
        sent().filter { it.topic == KafkaTopics.PRESENCE_UPDATE }
            .map { it.key to jsonMapper.readValue(it.value, PresenceEvent::class.java) }

    private fun publishedTyping(): List<Pair<String, TypingEvent>> =
        sent().filter { it.topic == KafkaTopics.TYPING_START }
            .map { it.key to jsonMapper.readValue(it.value, TypingEvent::class.java) }

    // ---- publishing ----

    @Test
    fun `going online publishes a transition keyed by the user, so a users own events stay ordered`() {
        presence.announceOnline("bob")

        val (key, event) = publishedPresence().single()
        assertEquals("bob", key, "keyed by user: an offline must never overtake a later online")
        assertEquals("bob", event.userId)
        assertEquals(PresenceStatuses.ONLINE, event.status)
        assertNull(event.lastSeen, "nobody needs a last-seen for somebody who is here")
    }

    @Test
    fun `going offline publishes the moment the observing node saw it`() {
        val wentOffline = Instant.parse("2026-08-13T10:00:00Z")

        presence.announceOffline("bob", wentOffline)

        val (key, event) = publishedPresence().single()
        assertEquals("bob", key)
        assertEquals(PresenceStatuses.OFFLINE, event.status)
        assertEquals(wentOffline, event.lastSeen, "stamped by the publisher, not by whoever consumes it")
    }

    @Test
    fun `typing publishes keyed by dialog, with recipients resolved here and the typist excluded`() {
        val alice = session("alice")

        presence.typing(alice, "d-1")

        val (key, event) = publishedTyping().single()
        assertEquals("d-1", key, "keyed by dialog, like every other dialog-scoped topic")
        assertEquals("alice", event.userId, "the typist comes from the session, never from the frame")
        assertEquals(
            listOf("bob"),
            event.recipientIds,
            "resolved server-side and excluding the typist — a client must not name its own recipients"
        )
    }

    @Test
    fun `typing into a dialog you are not in publishes nothing`() {
        val mallory = session("mallory")

        presence.typing(mallory, "d-1")

        assertTrue(publishedTyping().isEmpty())
        mallory.hasNoFrames() // no error frame either — unlike a send, and like a read
    }

    @Test
    fun `subscribing publishes nothing and is served entirely from this node`() {
        val alice = session("alice")

        assertEquals(PresenceSubscribeResult.Subscribed, presence.subscribe(alice, "d-1"))

        // A subscription is a fact about one connection on one node, and its snapshot is addressed to
        // that same connection — there is nothing for another node to learn.
        assertTrue(sent().isEmpty())
        assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame())
    }

    // ---- subscribing and the snapshot ----

    @Test
    fun `answers a subscription with a snapshot of each peer, excluding the subscriber`() {
        val bob = session("bob")
        val alice = session("alice")

        presence.subscribe(alice, "d-1")

        val update = assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame())
        assertEquals("bob", update.userId, "a client is not told about its own presence")
        assertEquals(PresenceStatus.ONLINE, update.status)
        assertNull(update.lastSeen, "an online peer needs no last-seen; the status says they are here")
        // One frame, not two: alice is a participant of d-1 but not a subject of it.
        alice.hasNoFrames()
        bob.hasNoFrames()
    }

    @Test
    fun `an offline peer is reported with the last time any node saw them`() {
        val bobPhone = session("bob")
        val wentOffline = Instant.parse("2026-08-13T10:00:00Z")
        registry.unregister(bobPhone)
        presence.announceOffline("bob", wentOffline)
        drainBroker()

        val alice = session("alice")
        presence.subscribe(alice, "d-1")

        val update = assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame())
        assertEquals(PresenceStatus.OFFLINE, update.status)
        assertEquals(wentOffline, update.lastSeen)
    }

    @Test
    fun `last-seen is learned from the event, so it is known even for a user this node never held`() {
        // Nobody is subscribed and bob has never connected here: the consuming side still records it,
        // which is what lets any node answer a later subscription.
        presence.deliver(PresenceEvent("bob", PresenceStatuses.OFFLINE, Instant.parse("2026-08-13T09:00:00Z")))

        val alice = session("alice")
        presence.subscribe(alice, "d-1")

        assertEquals(
            Instant.parse("2026-08-13T09:00:00Z"),
            assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame()).lastSeen
        )
    }

    @Test
    fun `a peer nobody has watched leave is offline with no last-seen`() {
        val alice = session("alice")

        presence.subscribe(alice, "d-1")

        val update = assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame())
        assertEquals(PresenceStatus.OFFLINE, update.status)
        assertNull(update.lastSeen, "a cold cluster forgets last-seen; null is the honest answer")
    }

    @Test
    fun `refuses a dialog the subscriber is not in, without saying whether it exists`() {
        val mallory = session("mallory")

        val rejected = assertIs<PresenceSubscribeResult.Rejected>(presence.subscribe(mallory, "d-1"))
        val unknown = assertIs<PresenceSubscribeResult.Rejected>(presence.subscribe(mallory, "d-nope"))

        assertEquals(ErrorCodes.DIALOG_NOT_FOUND, rejected.code)
        assertEquals(rejected.code, unknown.code, "the two must be indistinguishable")
        mallory.hasNoFrames()
    }

    @Test
    fun `a refused subscription leaves no subscription behind`() {
        val mallory = session("mallory")
        presence.subscribe(mallory, "d-1")

        presence.announceOnline("bob")
        drainBroker()

        mallory.hasNoFrames()
    }

    // ---- delivering ----

    @Test
    fun `tells subscribers when a peer comes online and when they go offline`() {
        val alice = session("alice")
        presence.subscribe(alice, "d-1")
        assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame())

        presence.announceOnline("bob")
        presence.announceOffline("bob", Instant.parse("2026-08-13T10:00:00Z"))
        drainBroker()

        assertEquals(PresenceStatus.ONLINE, assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame()).status)
        val offline = assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame())
        assertEquals(PresenceStatus.OFFLINE, offline.status)
        assertEquals(Instant.parse("2026-08-13T10:00:00Z"), offline.lastSeen)
    }

    @Test
    fun `reaches every device of a subscriber`() {
        val phone = session("alice")
        val web = session("alice")
        listOf(phone, web).forEach { presence.subscribe(it, "d-1") }
        listOf(phone, web).forEach { assertIs<OutboundFrame.PresenceUpdate>(it.nextFrame()) }

        presence.announceOnline("bob")
        drainBroker()

        listOf(phone, web).forEach {
            assertEquals(PresenceStatus.ONLINE, assertIs<OutboundFrame.PresenceUpdate>(it.nextFrame()).status)
        }
    }

    @Test
    fun `a transition for somebody nobody here watches is dropped`() {
        val alice = session("alice")

        // Every node consumes every transition; one holding no subscriber serves nothing.
        presence.deliver(PresenceEvent("carol", PresenceStatuses.ONLINE))

        alice.hasNoFrames()
    }

    @Test
    fun `an unrecognised status is delivered as offline rather than passed through`() {
        val alice = session("alice")
        presence.subscribe(alice, "d-1")
        assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame())

        presence.deliver(PresenceEvent("bob", "away"))

        // The event vocabulary is internal and the wire vocabulary is a client contract; they are
        // mapped, not forwarded, so a new internal status cannot leak onto the wire.
        assertEquals(PresenceStatus.OFFLINE, assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame()).status)
    }

    @Test
    fun `an unsubscribed session stops hearing about the peer`() {
        val alice = session("alice")
        presence.subscribe(alice, "d-1")
        assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame())

        presence.unsubscribe(alice, "d-1")
        presence.announceOnline("bob")
        drainBroker()

        alice.hasNoFrames()
    }

    @Test
    fun `unsubscribing something never subscribed is not an error`() {
        val alice = session("alice")

        presence.unsubscribe(alice, "d-1")
        presence.unsubscribe(alice, "d-1")

        alice.hasNoFrames()
    }

    @Test
    fun `a closed session is dropped from the fan-out`() {
        val alice = session("alice")
        presence.subscribe(alice, "d-1")
        assertIs<OutboundFrame.PresenceUpdate>(alice.nextFrame())

        presence.forget(alice)
        presence.announceOnline("bob")
        drainBroker()

        alice.hasNoFrames()
    }

    @Test
    fun `relays typing to the other participants and not to the typist`() {
        val alicePhone = session("alice")
        val aliceWeb = session("alice")
        val bob = session("bob")

        presence.typing(alicePhone, "d-1")
        drainBroker()

        val typing = assertIs<OutboundFrame.TypingStart>(bob.nextFrame())
        assertEquals("d-1", typing.dialogId)
        assertEquals("alice", typing.userId)
        alicePhone.hasNoFrames()
        aliceWeb.hasNoFrames() // "you are typing" on your own second device is a bug
    }

    @Test
    fun `typing needs no presence subscription`() {
        val alice = session("alice")
        val bob = session("bob")

        presence.typing(alice, "d-1")
        drainBroker()

        // Bob never subscribed to anything: an indicator is addressed to a conversation's members,
        // which is not the same relation as who is watching whose presence.
        assertIs<OutboundFrame.TypingStart>(bob.nextFrame())
    }

    @Test
    fun `an indicator for recipients held elsewhere costs this node nothing`() {
        val alice = session("alice")

        presence.deliver(TypingEvent("d-1", "carol", listOf("someone-on-another-node")))

        alice.hasNoFrames()
    }
}

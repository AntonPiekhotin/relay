package com.relay.websocket.fanout

import com.relay.common.model.UserPrincipal
import com.relay.websocket.output.socket.FrameDispatcher
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.InMemorySessionRegistry
import com.relay.websocket.session.RelaySession
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameDispatcherTest {

    private val registry = InMemorySessionRegistry()
    private val dispatcher = FrameDispatcher(registry)

    private var counter = 0

    private fun session(userId: String, bufferSize: Int = 16): RelaySession =
        RelaySession("s-${counter++}", UserPrincipal(userId, null, emptySet()), bufferSize)
            .also(registry::register)

    /**
     * The queue already holds whatever was dispatched, so this never actually parks — it just
     * takes the writer thread's place in these tests.
     */
    private fun RelaySession.nextFrame(): OutboundFrame =
        assertIs<RelaySession.Outbound.Frame>(awaitOutbound()).frame

    private fun messageFor(dialogId: String) = OutboundFrame.MessageNew(
        messageId = "m-1",
        dialogId = dialogId,
        senderId = "alice",
        text = "hi",
        createdAt = Instant.parse("2026-07-26T10:00:00Z")
    )

    @Test
    fun `delivers to every session of every recipient`() {
        val bobPhone = session("bob")
        val bobWeb = session("bob")
        val carol = session("carol")

        val delivered = dispatcher.deliverToUsers(listOf("bob", "carol"), messageFor("d-1"))

        assertEquals(3, delivered, "both of bob's sockets plus carol's")
        listOf(bobPhone, bobWeb, carol).forEach { session ->
            assertEquals("d-1", assertIs<OutboundFrame.MessageNew>(session.nextFrame()).dialogId)
        }
    }

    @Test
    fun `skips recipients that have no session`() {
        session("bob")

        val delivered = dispatcher.deliverToUsers(listOf("bob", "nobody-here"), messageFor("d-1"))

        assertEquals(1, delivered, "an offline recipient is not an error, it just gets nothing")
    }

    @Test
    fun `does not double-deliver when a recipient is listed twice`() {
        session("bob")

        assertEquals(1, dispatcher.deliverToUsers(listOf("bob", "bob"), messageFor("d-1")))
    }

    @Test
    fun `deliverToSession reaches exactly the sending device`() {
        val phone = session("bob")
        val web = session("bob")

        val ack = OutboundFrame.Ack("c-1", "m-1", Instant.parse("2026-07-26T10:00:00Z"))
        assertTrue(dispatcher.deliverToSession("bob", phone.sessionId, ack))

        assertEquals("c-1", assertIs<OutboundFrame.Ack>(phone.nextFrame()).clientMsgId)
        // The other device saw nothing: completing it puts the terminal marker at the head.
        web.complete()
        assertEquals(RelaySession.Outbound.Completed, web.awaitOutbound())
    }

    @Test
    fun `deliverToSession returns false for a session not held locally`() {
        session("bob")

        assertTrue(!dispatcher.deliverToSession("bob", "no-such-session", OutboundFrame.Pong()))
    }

    @Test
    fun `deliverToUsersExcept skips the acked session but reaches the same user's other devices`() {
        val sender = session("alice")
        val otherDevice = session("alice")
        val bob = session("bob")

        val delivered = dispatcher.deliverToUsersExcept(
            listOf("alice", "bob"), setOf(sender.sessionId), messageFor("d-1")
        )

        assertEquals(2, delivered, "the other device and bob, not the sending session")
        sender.complete()
        assertEquals(RelaySession.Outbound.Completed, sender.awaitOutbound())
        assertIs<OutboundFrame.MessageNew>(otherDevice.nextFrame())
        assertIs<OutboundFrame.MessageNew>(bob.nextFrame())
    }

    @Test
    fun `closes a session that falls behind its outbound buffer`() {
        val slow = session("bob", bufferSize = 1)

        assertEquals(1, dispatcher.deliverToUsers(listOf("bob"), messageFor("d-1")))
        assertEquals(0, dispatcher.deliverToUsers(listOf("bob"), messageFor("d-2")), "buffer is full")

        // Already-buffered frames are still handed over before the overload marker ends the stream.
        assertEquals("d-1", assertIs<OutboundFrame.MessageNew>(slow.nextFrame()).dialogId)
        assertEquals(RelaySession.Outbound.Overloaded, slow.awaitOutbound())
    }

    @Test
    fun `drops a frame that names no recipients`() {
        session("bob")

        assertEquals(0, dispatcher.deliverToUsers(emptyList(), messageFor("d-1")))
    }
}
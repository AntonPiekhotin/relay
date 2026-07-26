package com.relay.websocket.fanout

import com.relay.common.model.UserPrincipal
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.InMemorySessionRegistry
import com.relay.websocket.session.OutboundOverflowException
import com.relay.websocket.session.RelaySession
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import reactor.test.StepVerifier

class FrameDispatcherTest {

    private val registry = InMemorySessionRegistry()
    private val dispatcher = FrameDispatcher(registry)

    private var counter = 0

    private fun session(userId: String, bufferSize: Int = 16): RelaySession =
        RelaySession("s-${counter++}", UserPrincipal(userId, null, emptySet()), bufferSize)
            .also(registry::register)

    private fun messageFor(chatId: String) = OutboundFrame.MessageNew(
        id = "m-1",
        chatId = chatId,
        senderId = "alice",
        body = "hi",
        sentAt = Instant.parse("2026-07-26T10:00:00Z")
    )

    @Test
    fun `delivers to every session of every recipient`() {
        val bobPhone = session("bob")
        val bobWeb = session("bob")
        val carol = session("carol")

        val delivered = dispatcher.dispatch(listOf("bob", "carol"), messageFor("c-1"))

        assertEquals(3, delivered, "both of bob's sockets plus carol's")
        listOf(bobPhone, bobWeb, carol).forEach { session ->
            StepVerifier.create(session.frames)
                .assertNext { assertEquals("c-1", (it as OutboundFrame.MessageNew).chatId) }
                .thenCancel()
                .verify()
        }
    }

    @Test
    fun `skips recipients that have no session`() {
        val bob = session("bob")

        val delivered = dispatcher.dispatch(listOf("bob", "nobody-here"), messageFor("c-1"))

        assertEquals(1, delivered, "an offline recipient is not an error, it just gets nothing")
        StepVerifier.create(bob.frames).expectNextCount(1).thenCancel().verify()
    }

    @Test
    fun `does not double-deliver when a recipient is listed twice`() {
        session("bob")

        val delivered = dispatcher.dispatch(listOf("bob", "bob"), messageFor("c-1"))

        assertEquals(1, delivered)
    }

    @Test
    fun `closes a session that falls behind its outbound buffer`() {
        // Capacity one, and nothing is draining, so the second frame cannot be buffered.
        val slow = session("bob", bufferSize = 1)

        assertEquals(1, dispatcher.dispatch(listOf("bob"), messageFor("c-1")))
        assertEquals(0, dispatcher.dispatch(listOf("bob"), messageFor("c-2")), "buffer is full")

        StepVerifier.create(slow.frames)
            .assertNext { assertEquals("c-1", (it as OutboundFrame.MessageNew).chatId) }
            .expectError(OutboundOverflowException::class.java)
            .verify()
    }

    @Test
    fun `drops a frame that names no recipients`() {
        session("bob")

        assertEquals(0, dispatcher.dispatch(emptyList(), messageFor("c-1")))
    }
}
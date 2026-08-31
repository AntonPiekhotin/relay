package com.relay.websocket.input.web

import com.relay.common.model.UserPrincipal
import com.relay.websocket.session.InMemorySessionRegistry
import com.relay.websocket.session.RelaySession
import kotlin.test.Test
import kotlin.test.assertEquals

class InternalSessionControllerTest {

    private val registry = InMemorySessionRegistry()
    private val controller = InternalSessionController(registry)

    private var counter = 0

    private fun session(userId: String) =
        RelaySession("s-${counter++}", UserPrincipal(userId, null, emptySet()), 16)

    @Test
    fun `reports exactly the users holding a live session`() {
        registry.register(session("alice"))
        registry.register(session("bob"))

        assertEquals(setOf("alice", "bob"), controller.online(setOf("alice", "bob", "carol")))
    }

    @Test
    fun `a user whose last session closed is gone, one with another device is not`() {
        val phone = session("alice")
        registry.register(phone)
        registry.register(session("alice"))
        val only = session("bob")
        registry.register(only)

        registry.unregister(phone)
        registry.unregister(only)

        assertEquals(setOf("alice"), controller.online(setOf("alice", "bob")))
    }
}

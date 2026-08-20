package com.relay.websocket.presence

import com.relay.websocket.output.http.DialogMembershipResolver
import com.relay.websocket.output.http.DialogMembershipResult
import com.relay.websocket.protocol.ErrorCodes
import com.relay.websocket.util.MessageClientProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The cache exists so `typing.start` — one frame every three seconds per writing user — does not
 * become an HTTP call every three seconds. These tests pin the two things that could make it
 * dangerous rather than merely fast: that it still refuses a non-participant, and that it expires.
 */
class DialogMembershipResolverTest {

    private val client = StubDialogMembershipClient().withDialog("d-1", "alice", "bob")

    private fun resolver(ttl: Duration = Duration.ofMinutes(5), maxCached: Int = 10) =
        DialogMembershipResolver(client, MessageClientProperties(membershipTtl = ttl, maxCachedDialogs = maxCached))

    @Test
    fun `resolves once and serves repeats from the cache`() {
        val resolver = resolver()

        repeat(5) {
            assertEquals(
                listOf("alice", "bob"),
                assertIs<DialogMembershipResult.Found>(resolver.resolve("d-1", "alice")).participantIds
            )
        }

        assertEquals(1, client.lookups, "a typing burst must not become a burst of HTTP calls")
    }

    @Test
    fun `a cached dialog still refuses somebody who is not in it`() {
        val resolver = resolver()
        resolver.resolve("d-1", "alice")

        val rejected = assertIs<DialogMembershipResult.Rejected>(resolver.resolve("d-1", "mallory"))

        assertEquals(ErrorCodes.DIALOG_NOT_FOUND, rejected.code)
        assertEquals(1, client.lookups, "the cached membership answers the same question locally")
    }

    @Test
    fun `a rejection is never cached`() {
        val resolver = resolver()

        repeat(3) { assertIs<DialogMembershipResult.Rejected>(resolver.resolve("d-unknown", "alice")) }

        assertEquals(3, client.lookups, "a dialog that starts existing must not stay refused")
    }

    @Test
    fun `an expired entry is resolved again`() {
        val resolver = resolver(ttl = Duration.ZERO)

        resolver.resolve("d-1", "alice")
        resolver.resolve("d-1", "alice")

        assertEquals(2, client.lookups)
    }

    @Test
    fun `invalidation forces a re-fetch inside the TTL`() {
        val resolver = resolver()
        resolver.resolve("d-1", "alice")
        resolver.resolve("d-1", "alice")
        assertEquals(1, client.lookups)

        resolver.invalidate("d-1")

        // The next resolve sees the post-change membership — the group-change path relies on this
        // being immediate, because the cached list is also the authorization answer.
        client.withDialog("d-1", "alice")
        val fresh = assertIs<DialogMembershipResult.Found>(resolver.resolve("d-1", "alice"))
        assertEquals(listOf("alice"), fresh.participantIds)
        assertEquals(2, client.lookups)
        assertIs<DialogMembershipResult.Rejected>(resolver.resolve("d-1", "bob"), "the removed member is out now")
    }

    @Test
    fun `a full cache keeps answering, it just stops caching`() {
        val resolver = resolver(maxCached = 1)
        client.withDialog("d-2", "alice", "carol")

        resolver.resolve("d-1", "alice")
        repeat(2) {
            assertIs<DialogMembershipResult.Found>(resolver.resolve("d-2", "alice"))
        }

        // d-1 filled the single slot and is still live, so d-2 goes uncached rather than evicting it.
        assertEquals(3, client.lookups)
    }
}

package com.relay.call.output.sfu

import com.auth0.jwt.JWT
import com.relay.call.config.CallProperties
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The token is the room's admission control, so what matters is what it claims: the right room,
 * the right identity, signed by the configured key, and short-lived. Pure unit test — minting is
 * offline signing, no LiveKit involved.
 */
class LivekitTokenFactoryTest {

    private val properties = CallProperties(
        livekit = CallProperties.Livekit(
            url = "ws://sfu.example:7880",
            apiKey = "test-key",
            apiSecret = "0123456789abcdef0123456789abcdef",
            tokenTtl = Duration.ofMinutes(5)
        )
    )

    private val factory = LivekitTokenFactory(properties)

    @Test
    fun `the token admits exactly this user to exactly this room`() {
        val token = factory.joinToken(room = "room-1", identity = "alice")

        val decoded = JWT.decode(token.token)
        assertEquals("alice", decoded.subject, "identity rides in the subject")
        assertEquals("test-key", decoded.issuer, "issued under the configured api key")
        val grant = decoded.getClaim("video").asMap()
        assertEquals("room-1", grant["room"])
        assertEquals(true, grant["roomJoin"])
    }

    @Test
    fun `the token is short-lived and says where to connect`() {
        val before = Instant.now()
        val token = factory.joinToken(room = "room-2", identity = "bob")

        assertEquals("ws://sfu.example:7880", token.url)
        assertTrue(token.expiresAt.isAfter(before), "not already expired")
        assertTrue(
            token.expiresAt.isBefore(before.plus(Duration.ofMinutes(6))),
            "bounded by the configured ttl, not the SDK's six-hour default"
        )
        val exp = JWT.decode(token.token).expiresAtAsInstant
        assertTrue(exp.isBefore(before.plus(Duration.ofMinutes(6))), "the claim itself is bounded too")
    }

    @Test
    fun `every mint is fresh — rejoining refreshes rather than reuses`() {
        val first = factory.joinToken(room = "room-3", identity = "carol")
        Thread.sleep(1100) // JWT timestamps have second precision
        val second = factory.joinToken(room = "room-3", identity = "carol")
        assertTrue(
            second.expiresAt.isAfter(first.expiresAt),
            "a later mint expires later — the refresh path actually refreshes"
        )
    }
}

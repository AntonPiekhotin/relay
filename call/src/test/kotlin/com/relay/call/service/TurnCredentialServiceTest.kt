package com.relay.call.service

import com.relay.call.config.CallProperties
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The credential format is not ours to choose — coturn's `use-auth-secret` mode verifies exactly
 * `username = <expiry-unix-seconds>:<userId>` and `password = base64(hmac-sha1(secret, username))`,
 * and gets a 401 for anything else. These assertions pin the shape; the scheme itself was checked
 * against a real coturn, which accepts a credential built this way and refuses both a wrong one and
 * an expired one.
 */
class TurnCredentialServiceTest {

    private fun service(
        urls: List<String> = listOf(
            "stun:localhost:3478",
            "turn:localhost:3478?transport=udp",
            "turn:localhost:3478?transport=tcp"
        ),
        ttl: Duration = Duration.ofHours(12)
    ) = TurnCredentialService(
        CallProperties(
            turn = CallProperties.Turn(
                urls = urls,
                staticAuthSecret = "relay-turn-dev-secret",
                credentialTtl = ttl
            )
        )
    )

    @Test
    fun `separates stun from turn, because only turn needs credentials`() {
        val response = service().iceServersFor("user-42")

        assertEquals(2, response.iceServers.size)
        val stun = response.iceServers.first()
        assertEquals(listOf("stun:localhost:3478"), stun.urls)
        assertNull(stun.username, "STUN does not authenticate")
        assertNull(stun.credential)

        val turn = response.iceServers.last()
        assertEquals(
            listOf("turn:localhost:3478?transport=udp", "turn:localhost:3478?transport=tcp"),
            turn.urls,
            "both transports share one credential"
        )
        assertNotNull(turn.username)
        assertNotNull(turn.credential)
    }

    @Test
    fun `the username is the expiry and the user id, in that order`() {
        val before = Instant.now()
        val response = service(ttl = Duration.ofHours(12)).iceServersFor("user-42")

        val username = response.iceServers.last().username!!
        val (expiry, userId) = username.split(":", limit = 2)
        assertEquals("user-42", userId)

        val expiresAt = Instant.ofEpochSecond(expiry.toLong())
        assertTrue(
            expiresAt >= before.plus(Duration.ofHours(12)).minusSeconds(5) &&
                expiresAt <= before.plus(Duration.ofHours(12)).plusSeconds(5),
            "expiry should be now + ttl, was $expiresAt"
        )
        assertEquals(Duration.ofHours(12).seconds, response.ttlSeconds)
    }

    @Test
    fun `the credential is base64 of a 20-byte sha1 hmac, not hex and not the secret`() {
        val credential = service().iceServersFor("user-42").iceServers.last().credential!!

        val decoded = Base64.getDecoder().decode(credential)
        assertEquals(20, decoded.size, "HMAC-SHA1 is 20 bytes; a different length means a different digest")
        assertTrue(!credential.contains("relay-turn-dev-secret"), "the shared secret must never leave the server")
    }

    @Test
    fun `a colon in the user id does not corrupt the expiry`() {
        // Keycloak subjects are UUIDs, but splitting on the first colon only is what makes that
        // an assumption rather than a requirement.
        val username = service().iceServersFor("odd:user").iceServers.last().username!!

        val (expiry, userId) = username.split(":", limit = 2)
        assertTrue(expiry.toLong() > 0)
        assertEquals("odd:user", userId)
    }

    @Test
    fun `two users never share a credential`() {
        val one = service().iceServersFor("user-1").iceServers.last()
        val two = service().iceServersFor("user-2").iceServers.last()

        assertTrue(one.credential != two.credential, "a shared password is an open relay waiting to happen")
    }

    @Test
    fun `a deployment with no turn server returns stun alone rather than empty credentials`() {
        val response = service(urls = listOf("stun:stun.l.google.com:19302")).iceServersFor("user-42")

        assertEquals(1, response.iceServers.size)
        assertNull(response.iceServers.single().username)
    }
}

package com.relay.call.input.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.relay.call.PostgresTestcontainerConfig
import com.relay.call.model.dto.CreateGroupCallRequest
import com.relay.call.repository.ActiveCallRepository
import com.relay.call.service.GroupCallService
import com.relay.call.service.sfu.RoomDirectory
import com.relay.common.event.KafkaTopics
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The webhook endpoint's contract: a request signed the way LiveKit signs — an HMAC-SHA256 JWT
 * over the api secret whose `sha256` claim is the Base64 digest of the exact body — is acted on;
 * anything else is refused before a field is read. The signature is the *only* guard, because the
 * route sits on the `permitAll` `/internal` surface.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "relay.call.sweep-interval=1h",
        "relay.call.reconcile-interval=1h"
    ]
)
@AutoConfigureMockMvc
@Import(PostgresTestcontainerConfig::class, LivekitWebhookIT.NoopSfuConfig::class)
@EmbeddedKafka(partitions = 1, topics = [KafkaTopics.CALL_SIGNAL, KafkaTopics.NOTIFICATIONS])
class LivekitWebhookIT {

    @TestConfiguration
    class NoopSfuConfig {
        @Bean
        @Primary
        fun noopRoomDirectory(): RoomDirectory = object : RoomDirectory {
            override fun participantIdentities(room: String): Set<String>? = null
            override fun closeRoom(room: String) = Unit
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var groupCallService: GroupCallService
    @Autowired private lateinit var activeCallRepository: ActiveCallRepository

    @Value("\${relay.call.livekit.api-secret}")
    private lateinit var apiSecret: String

    @Value("\${relay.call.livekit.api-key}")
    private lateinit var apiKey: String

    private fun signedAuthHeader(body: String, secret: String = apiSecret): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
        return JWT.create()
            .withIssuer(apiKey)
            .withClaim("sha256", Base64.getEncoder().encodeToString(digest))
            .sign(Algorithm.HMAC256(secret))
    }

    private fun postWebhook(body: String, authHeader: String?) =
        mockMvc.perform(
            post("/internal/api/v1/livekit/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .apply { authHeader?.let { header(HttpHeaders.AUTHORIZATION, it) } }
        )

    private fun participantLeftBody(room: String, identity: String) =
        """{"event":"participant_left","room":{"name":"$room"},"participant":{"identity":"$identity"}}"""

    private fun roomFinishedBody(room: String) =
        """{"event":"room_finished","room":{"name":"$room"}}"""

    private fun ringingGroupCall(caller: String, vararg invitees: String): String =
        groupCallService.create(
            caller,
            CreateGroupCallRequest(
                callId = UUID.randomUUID().toString(),
                media = "audio",
                inviteeIds = invitees.toList()
            )
        ).response.callId

    @Test
    fun `a signed participant_left lands as a leave`() {
        val callId = ringingGroupCall("wh-alice", "wh-bob", "wh-carol")
        groupCallService.join("wh-bob", callId, null)

        val body = participantLeftBody(callId, "wh-bob")
        postWebhook(body, signedAuthHeader(body)).andExpect(status().isOk)

        assertFalse(activeCallRepository.findById("wh-bob").isPresent, "the vanished participant is freed")
        assertEquals("answered", groupCallService.describe("wh-alice", callId).status)
    }

    @Test
    fun `a signed room_finished ends the call`() {
        val callId = ringingGroupCall("whf-alice", "whf-bob")
        groupCallService.join("whf-bob", callId, null)

        val body = roomFinishedBody(callId)
        postWebhook(body, signedAuthHeader(body)).andExpect(status().isOk)

        assertEquals("ended", groupCallService.describe("whf-alice", callId).status)
        assertFalse(activeCallRepository.findById("whf-alice").isPresent)
    }

    @Test
    fun `a tampered or missing signature is refused before any state changes`() {
        val callId = ringingGroupCall("wht-alice", "wht-bob")
        groupCallService.join("wht-bob", callId, null)
        val body = participantLeftBody(callId, "wht-bob")

        postWebhook(body, signedAuthHeader(body, secret = "wrong-secret-that-is-long-enough!!"))
            .andExpect(status().isUnauthorized)
        // A signature over a *different* body must fail too — that is the sha256 claim's whole job.
        postWebhook(body, signedAuthHeader(participantLeftBody(callId, "wht-alice")))
            .andExpect(status().isUnauthorized)
        postWebhook(body, authHeader = null).andExpect(status().isUnauthorized)

        assertTrue(
            activeCallRepository.findById("wht-bob").isPresent,
            "an unauthenticated webhook must not have moved anything"
        )
    }

    @Test
    fun `unknown rooms and foreign room names are acknowledged without action`() {
        val unknownRoom = participantLeftBody(UUID.randomUUID().toString(), "nobody")
        postWebhook(unknownRoom, signedAuthHeader(unknownRoom)).andExpect(status().isOk)

        val foreignRoom = participantLeftBody("not-a-call-id", "nobody")
        postWebhook(foreignRoom, signedAuthHeader(foreignRoom)).andExpect(status().isOk)
    }
}

package com.relay.call.input.web

import com.relay.call.service.GroupCallService
import io.livekit.server.WebhookReceiver
import java.util.UUID
import livekit.LivekitWebhook
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * LiveKit's account of what happened in a room — the authority for participants who vanished
 * without saying goodbye, because a killed app sends no REST leave.
 *
 * Under `/internal` and therefore `permitAll` in the security chain, but NOT identity-trusting the
 * way the other `/internal` routes are: every request's `Authorization` header carries a JWT signed
 * with the LiveKit API secret, and [WebhookReceiver] verifies both the signature and the body's
 * sha256 before a single field is read. A request that fails that check is a 401. The api-gateway
 * still never routes `/internal`, so the only parties who can reach this at all are the LiveKit
 * container and other services.
 *
 * The body is bound as a raw [String] on purpose: the signature covers the exact bytes, so letting
 * a message converter reshape the JSON first would break verification.
 *
 * Handlers answer 200 even for rooms they do not recognise — LiveKit retries non-2xx responses,
 * and a webhook for a room this service never made (or already forgot) will never become
 * processable. Every dispatched transition is idempotent, because LiveKit does not guarantee
 * ordering or exactly-once delivery.
 */
@RestController
@RequestMapping(path = ["/internal/api/v1/livekit"])
class LivekitWebhookController(
    private val webhookReceiver: WebhookReceiver,
    private val groupCallService: GroupCallService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping("/webhooks")
    fun receive(
        @RequestBody body: String,
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?
    ): ResponseEntity<Void> {
        val event = try {
            webhookReceiver.receive(body, authorization)
        } catch (ex: Exception) {
            logger.warn("Rejected a LiveKit webhook with a bad or missing signature: {}", ex.message)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        when (event.event) {
            PARTICIPANT_LEFT -> callIdOf(event)?.let {
                groupCallService.onSfuParticipantLeft(it, event.participant.identity)
            }
            ROOM_FINISHED -> callIdOf(event)?.let {
                groupCallService.onSfuRoomFinished(it)
            }
            // participant_joined is deliberately ignored: REST join is the authority — holding a
            // valid room token proves the join already happened on our side.
            else -> logger.debug("Ignoring LiveKit webhook '{}'", event.event)
        }
        return ResponseEntity.ok().build()
    }

    /** Room names are call ids; anything else is not ours and is acknowledged without action. */
    private fun callIdOf(event: LivekitWebhook.WebhookEvent): UUID? {
        val roomName = event.room.name
        return try {
            UUID.fromString(roomName)
        } catch (ex: IllegalArgumentException) {
            logger.debug("Ignoring LiveKit webhook '{}' for foreign room '{}'", event.event, roomName)
            null
        }
    }

    private companion object {
        const val PARTICIPANT_LEFT = "participant_left"
        const val ROOM_FINISHED = "room_finished"
    }
}

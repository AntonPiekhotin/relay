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
            else -> logger.debug("Ignoring LiveKit webhook '{}'", event.event)
        }
        return ResponseEntity.ok().build()
    }

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

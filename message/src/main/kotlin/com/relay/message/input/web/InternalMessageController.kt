package com.relay.message.input.web

import com.relay.common.dto.DialogParticipantsResponse
import com.relay.common.dto.MessageResponse
import com.relay.common.dto.SendMessageRequest
import com.relay.message.model.dto.CreateDialogRequest
import com.relay.message.model.dto.DialogResponse
import com.relay.message.service.DialogQueryService
import com.relay.message.service.DialogService
import com.relay.message.service.MessageService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Service-to-service only, under `/internal` so it is not reachable through the api-gateway.
 * That boundary is load-bearing: [SendMessageRequest.senderId] is trusted here.
 *
 * [send] is the REST fallback send path — it converges on the same
 * [MessageService] the Kafka consumer uses, and the HTTP response plays the role of the ack.
 */
@RestController
@RequestMapping(path = ["/internal/api/v1"])
class InternalMessageController(
    private val messageService: MessageService,
    private val dialogService: DialogService,
    private val dialogQueryService: DialogQueryService
) {

    /**
     * 201 for a stored message, 200 when an existing message was returned for a repeated
     * clientMessageId — so a caller can tell a fresh send from a recognised retry.
     */
    @PostMapping("/messages")
    fun send(@Valid @RequestBody request: SendMessageRequest): ResponseEntity<MessageResponse> {
        val result = messageService.send(request)
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(result.message)
    }

    @PostMapping("/dialogs")
    fun createDialog(@Valid @RequestBody request: CreateDialogRequest): ResponseEntity<DialogResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(dialogService.create(request))

    /**
     * Who receives presence and typing for this dialog. The gateway relays both to people, not to a
     * dialog, and membership is data only this service holds.
     *
     * [callerId] is trusted, like every other `/internal` identity — the gateway takes it from the
     * authenticated socket. It is not decoration: the lookup is scoped to it, so a caller who is not
     * a participant gets **404**, the same answer as a dialog that does not exist. Without that, a
     * client could walk dialog ids and learn which conversations are real, and subscribe to the
     * presence of people it has no conversation with.
     */
    @GetMapping("/dialogs/{dialogId}/participants")
    fun participants(
        @PathVariable dialogId: String,
        @RequestParam callerId: String
    ): DialogParticipantsResponse {
        val dialog = dialogQueryService.requireParticipant(callerId, dialogId)
        return DialogParticipantsResponse(
            dialogId = dialog.id.toString(),
            participantIds = dialog.participantIds.toList()
        )
    }
}
package com.relay.message.input.web

import com.relay.common.dto.MessageResponse
import com.relay.common.dto.SendMessageRequest
import com.relay.message.model.dto.CreateDialogRequest
import com.relay.message.model.dto.DialogResponse
import com.relay.message.service.DialogService
import com.relay.message.service.MessageService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
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
    private val dialogService: DialogService
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
}
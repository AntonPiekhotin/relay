package com.relay.message.input.web

import com.relay.common.exception.RelayException
import com.relay.message.model.dto.DialogListResponse
import com.relay.message.model.dto.DialogResponse
import com.relay.message.model.dto.DialogSummaryResponse
import com.relay.message.model.dto.MessageHistoryResponse
import com.relay.message.model.dto.OpenDirectDialogRequest
import com.relay.message.model.dto.ReadStateResponse
import com.relay.message.service.DialogQueryService
import com.relay.message.service.DialogService
import com.relay.message.service.MessageHistoryService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(path = ["/api/v1/message/dialogs"])
class DialogController(
    private val dialogService: DialogService,
    private val dialogQueryService: DialogQueryService,
    private val messageHistoryService: MessageHistoryService
) {

    @PostMapping
    fun openDirect(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: OpenDirectDialogRequest
    ): ResponseEntity<DialogResponse> {
        val result = dialogService.openDirect(jwt.callerId(), request.peerId)
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(result.dialog)
    }

    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?
    ): ResponseEntity<DialogListResponse> =
        ResponseEntity.ok(dialogQueryService.list(jwt.callerId(), cursor, limit))

    /** The seen-by snapshot: every member's read cursor. */
    @GetMapping("/{dialogId}/read-state")
    fun readState(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable dialogId: String
    ): ResponseEntity<ReadStateResponse> =
        ResponseEntity.ok(dialogQueryService.readState(jwt.callerId(), dialogId))

    @GetMapping("/{dialogId}")
    fun metadata(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable dialogId: String
    ): ResponseEntity<DialogSummaryResponse> =
        ResponseEntity.ok(dialogQueryService.metadata(jwt.callerId(), dialogId))

    @GetMapping("/{dialogId}/messages")
    fun history(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable dialogId: String,
        @RequestParam(required = false) before: String?,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = false) limit: Int?
    ): ResponseEntity<MessageHistoryResponse> =
        ResponseEntity.ok(messageHistoryService.history(jwt.callerId(), dialogId, before, after, limit))

    private fun Jwt.callerId(): String =
        subject ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no subject")
}

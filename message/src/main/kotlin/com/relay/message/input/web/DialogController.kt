package com.relay.message.input.web

import com.relay.common.exception.RelayException
import com.relay.message.model.dto.DialogResponse
import com.relay.message.model.dto.OpenDirectDialogRequest
import com.relay.message.service.DialogService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The client-facing half of message-service, reached through the api-gateway's `/api/v1/message`
 * route — which is why the path carries the `message` segment.
 *
 * One endpoint for now, and it is the one that was missing: without it a client could find a user
 * and add them as a contact but had no way to obtain a dialog id, and every send needs one. History
 * and the dialog list are still absent.
 *
 * The caller comes from the token, never from the body. `/internal` may trust a supplied id because
 * only other services reach it; here anyone holding a token does.
 */
@RestController
@RequestMapping(path = ["/api/v1/message/dialogs"])
class DialogController(
    private val dialogService: DialogService
) {

    /** 201 when this call opened the dialog, 200 when it was already there — a repeat is not an error. */
    @PostMapping
    fun openDirect(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: OpenDirectDialogRequest
    ): ResponseEntity<DialogResponse> {
        val result = dialogService.openDirect(jwt.callerId(), request.peerId)
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(result.dialog)
    }

    /** Without a `sub` there is no user to act as — refuse rather than guess. */
    private fun Jwt.callerId(): String =
        subject ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no subject")
}

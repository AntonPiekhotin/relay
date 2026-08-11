package com.relay.message.input.web

import com.relay.common.exception.RelayException
import com.relay.message.model.dto.DialogListResponse
import com.relay.message.model.dto.DialogResponse
import com.relay.message.model.dto.DialogSummaryResponse
import com.relay.message.model.dto.MessageHistoryResponse
import com.relay.message.model.dto.OpenDirectDialogRequest
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

/**
 * The client-facing half of message-service, reached through the api-gateway's `/api/v1/message`
 * route — which is why the path carries the `message` segment.
 *
 * Pull over HTTP, push over the socket (`docs/PROTOCOL.md` §1). Sending and delivery are frames;
 * what lives here is everything a client fetches — the dialog it opens a conversation in, the list
 * that makes conversations discoverable at all, and the history that makes the socket allowed to be
 * lossy.
 *
 * The caller comes from the token, never from the body or the path. `/internal` may trust a supplied
 * id because only other services reach it; here anyone holding a token does.
 *
 * Bodies are camelCase, like every other implemented REST surface and unlike the snake_case frames.
 * The gateway keeps a separate mapper for the wire protocol for exactly that reason.
 */
@RestController
@RequestMapping(path = ["/api/v1/message/dialogs"])
class DialogController(
    private val dialogService: DialogService,
    private val dialogQueryService: DialogQueryService,
    private val messageHistoryService: MessageHistoryService
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

    /**
     * The caller's dialogs, most recently active first, each with their own unread count.
     *
     * Unpaginated by contract — see `DialogQueryRepository.findAllForUser` for why that is safe today
     * and what would change it.
     */
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<DialogListResponse> =
        ResponseEntity.ok(dialogQueryService.list(jwt.callerId()))

    /** One dialog. 404 rather than 403 when the caller is not in it — see [DialogQueryService]. */
    @GetMapping("/{dialogId}")
    fun metadata(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable dialogId: String
    ): ResponseEntity<DialogSummaryResponse> =
        ResponseEntity.ok(dialogQueryService.metadata(jwt.callerId(), dialogId))

    /**
     * History, one page per call.
     *
     * `before` scrolls back and returns newest-first; `after` catches up from a known position and
     * returns oldest-first. Both cursors are message ids the client already holds and are exclusive
     * of the message they name. Neither given yields the newest page. Passing both is a `400`.
     *
     * `limit` is clamped to the configured maximum rather than rejected, so a client asking for too
     * much gets the maximum instead of an error it has to handle.
     */
    @GetMapping("/{dialogId}/messages")
    fun history(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable dialogId: String,
        @RequestParam(required = false) before: String?,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = false) limit: Int?
    ): ResponseEntity<MessageHistoryResponse> =
        ResponseEntity.ok(messageHistoryService.history(jwt.callerId(), dialogId, before, after, limit))

    /** Without a `sub` there is no user to act as — refuse rather than guess. */
    private fun Jwt.callerId(): String =
        subject ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no subject")
}

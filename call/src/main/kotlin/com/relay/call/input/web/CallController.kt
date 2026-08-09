package com.relay.call.input.web

import com.relay.call.model.dto.CallHistoryResponse
import com.relay.call.model.dto.IceServersResponse
import com.relay.call.service.CallService
import com.relay.call.service.TurnCredentialService
import com.relay.common.exception.RelayException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The client-facing half. Pull over HTTP, push over the socket: everything time-critical about a
 * call is a frame, and what is left here is what a client fetches — the servers it needs to build a
 * peer connection, and the log of what already happened.
 *
 * The api-gateway routes everything under `/api/v1/call` here, which is why the paths below carry
 * the `call` segment.
 */
@RestController
@RequestMapping(path = ["/api/v1/call"])
class CallController(
    private val callService: CallService,
    private val turnCredentialService: TurnCredentialService
) {

    /**
     * STUN and TURN servers with short-lived credentials. A client fetches this before placing or
     * answering a call and refetches when `ttlSeconds` is close to elapsing.
     */
    @GetMapping("/ice-servers")
    fun iceServers(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<IceServersResponse> =
        ResponseEntity.ok(turnCredentialService.iceServersFor(jwt.callerId()))

    /**
     * The caller's own call log, newest first. `before` is the id of the oldest call already held —
     * cursor, never offset, because calls insert at the head.
     */
    @GetMapping("/calls")
    fun history(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) before: String?,
        @RequestParam(required = false) limit: Int?
    ): ResponseEntity<CallHistoryResponse> =
        ResponseEntity.ok(callService.history(jwt.callerId(), before, limit))

    /** Without a `sub` there is no user to answer for — refuse rather than guess. */
    private fun Jwt.callerId(): String =
        subject ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no subject")
}

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

@RestController
@RequestMapping(path = ["/api/v1/call"])
class CallController(
    private val callService: CallService,
    private val turnCredentialService: TurnCredentialService
) {

    @GetMapping("/ice-servers")
    fun iceServers(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<IceServersResponse> =
        ResponseEntity.ok(turnCredentialService.iceServersFor(jwt.callerId()))

    @GetMapping("/calls")
    fun history(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) before: String?,
        @RequestParam(required = false) limit: Int?
    ): ResponseEntity<CallHistoryResponse> =
        ResponseEntity.ok(callService.history(jwt.callerId(), before, limit))

    private fun Jwt.callerId(): String =
        subject ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no subject")
}

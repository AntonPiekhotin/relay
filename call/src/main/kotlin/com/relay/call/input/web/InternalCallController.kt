package com.relay.call.input.web

import com.relay.call.model.dto.CallResponse
import com.relay.call.service.CallService
import com.relay.common.dto.AcceptCallRequest
import com.relay.common.dto.HangupCallRequest
import com.relay.common.dto.IceCandidateRequest
import com.relay.common.dto.InviteCallRequest
import com.relay.common.dto.RejectCallRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Service-to-service only, under `/internal` so it is not reachable through the api-gateway.
 *
 * That boundary is load-bearing: the `userId` on every request below is trusted, because
 * websocket-gateway took it from an authenticated socket rather than from the client's frame.
 * Exposing these routes would be an authentication bypass, not an information leak.
 *
 * Signaling arrives here over HTTP rather than Kafka because the gateway needs an answer — a call
 * that cannot start must fail the client's frame now, not asynchronously. The relay back out to
 * participants is the half that runs on Kafka.
 */
@RestController
@RequestMapping(path = ["/internal/api/v1/calls"])
class InternalCallController(private val callService: CallService) {

    @PostMapping("/invite")
    fun invite(@Valid @RequestBody request: InviteCallRequest): ResponseEntity<CallResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(callService.invite(request))

    @PostMapping("/{callId}/accept")
    fun accept(
        @PathVariable callId: String,
        @Valid @RequestBody request: AcceptCallRequest
    ): ResponseEntity<CallResponse> = ResponseEntity.ok(callService.accept(callId, request))

    @PostMapping("/{callId}/reject")
    fun reject(
        @PathVariable callId: String,
        @Valid @RequestBody request: RejectCallRequest
    ): ResponseEntity<CallResponse> = ResponseEntity.ok(callService.reject(callId, request))

    @PostMapping("/{callId}/hangup")
    fun hangup(
        @PathVariable callId: String,
        @Valid @RequestBody request: HangupCallRequest
    ): ResponseEntity<CallResponse> = ResponseEntity.ok(callService.hangup(callId, request))

    /**
     * 202, and no body: a candidate is a fire-and-forget relay, and it may be held for a call that
     * does not exist yet. There is nothing meaningful to return, and ICE is the one path where the
     * per-request cost actually shows — a full gathering round is dozens of these.
     */
    @PostMapping("/{callId}/ice")
    fun ice(
        @PathVariable callId: String,
        @Valid @RequestBody request: IceCandidateRequest
    ): ResponseEntity<Void> {
        callService.relayIce(callId, request)
        return ResponseEntity.accepted().build()
    }
}

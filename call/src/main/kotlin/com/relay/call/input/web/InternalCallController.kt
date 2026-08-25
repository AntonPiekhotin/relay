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

@RestController
@RequestMapping(path = ["/internal/api/v1/calls"])
class InternalCallController(
    private val callService: CallService
) {

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

    @PostMapping("/{callId}/ice")
    fun ice(
        @PathVariable callId: String,
        @Valid @RequestBody request: IceCandidateRequest
    ): ResponseEntity<Void> {
        callService.relayIce(callId, request)
        return ResponseEntity.accepted().build()
    }
}

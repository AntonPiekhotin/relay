package com.relay.call.input.web

import com.relay.call.model.dto.CreateGroupCallRequest
import com.relay.call.model.dto.DeclineGroupCallRequest
import com.relay.call.model.dto.GroupCallResponse
import com.relay.call.model.dto.JoinGroupCallRequest
import com.relay.call.model.dto.LeaveGroupCallRequest
import com.relay.call.service.GroupCallService
import com.relay.common.exception.RelayException
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
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(path = ["/api/v1/call/group-calls"])
class GroupCallController(
    private val groupCallService: GroupCallService
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateGroupCallRequest
    ): ResponseEntity<GroupCallResponse> {
        val result = groupCallService.create(jwt.callerId(), request)
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(result.response)
    }

    @PostMapping("/{callId}/join")
    fun join(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable callId: String,
        @RequestBody(required = false) request: JoinGroupCallRequest?
    ): ResponseEntity<GroupCallResponse> =
        ResponseEntity.ok(groupCallService.join(jwt.callerId(), callId, request?.sessionId))

    @PostMapping("/{callId}/decline")
    fun decline(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable callId: String,
        @RequestBody(required = false) request: DeclineGroupCallRequest?
    ): ResponseEntity<GroupCallResponse> =
        ResponseEntity.ok(groupCallService.decline(jwt.callerId(), callId, request?.reason, request?.sessionId))

    @PostMapping("/{callId}/leave")
    fun leave(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable callId: String,
        @RequestBody(required = false) request: LeaveGroupCallRequest?
    ): ResponseEntity<GroupCallResponse> =
        ResponseEntity.ok(groupCallService.leave(jwt.callerId(), callId, request?.sessionId))

    @GetMapping("/{callId}")
    fun describe(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable callId: String
    ): ResponseEntity<GroupCallResponse> =
        ResponseEntity.ok(groupCallService.describe(jwt.callerId(), callId))

    private fun Jwt.callerId(): String =
        subject ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no subject")
}

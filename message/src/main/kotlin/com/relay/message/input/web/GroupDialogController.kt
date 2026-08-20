package com.relay.message.input.web

import com.relay.common.exception.RelayException
import com.relay.message.model.dto.AddMembersRequest
import com.relay.message.model.dto.CreateGroupDialogRequest
import com.relay.message.model.dto.DialogSummaryResponse
import com.relay.message.model.dto.RenameGroupRequest
import com.relay.message.service.DialogQueryService
import com.relay.message.service.GroupDialogService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Group management, client-facing. All of it is REST rather than frames: none of these operations
 * needs the socket's ordering, every one wants a synchronous status, and the frames a change *does*
 * produce (`message.system`, `dialog.deleted`) fan out from the Kafka event once the transaction
 * commits.
 *
 * The status vocabulary follows the rest of the service: an outsider gets **404** (a guessed id
 * must look like no dialog at all — `DialogQueryService.requireParticipant`), a member who is not
 * the owner gets **403** (they already know the group exists), a mutation aimed at a direct dialog
 * gets **400** (the caller holds the real id; the operation is the mistake).
 */
@RestController
@RequestMapping(path = ["/api/v1/message/dialogs"])
class GroupDialogController(
    private val groupDialogService: GroupDialogService,
    private val dialogQueryService: DialogQueryService
) {

    /**
     * 201 for a created group, 200 when [CreateGroupDialogRequest.dialogId] named a group this
     * caller already created — a retried create converges instead of making a twin. An id that is
     * taken by anything else is a 409.
     */
    @PostMapping("/group")
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateGroupDialogRequest
    ): ResponseEntity<DialogSummaryResponse> {
        val callerId = jwt.callerId()
        val result = groupDialogService.create(callerId, request)
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(summary(callerId, result.dialogId.toString()))
    }

    @PutMapping("/{dialogId}/title")
    fun rename(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable dialogId: String,
        @Valid @RequestBody request: RenameGroupRequest
    ): ResponseEntity<DialogSummaryResponse> {
        val callerId = jwt.callerId()
        groupDialogService.rename(callerId, dialogId, request.title)
        return ResponseEntity.ok(summary(callerId, dialogId))
    }

    @PostMapping("/{dialogId}/members")
    fun addMembers(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable dialogId: String,
        @Valid @RequestBody request: AddMembersRequest
    ): ResponseEntity<DialogSummaryResponse> {
        val callerId = jwt.callerId()
        groupDialogService.addMembers(callerId, dialogId, request.userIds)
        return ResponseEntity.ok(summary(callerId, dialogId))
    }

    @DeleteMapping("/{dialogId}/members/{userId}")
    fun removeMember(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable dialogId: String,
        @PathVariable userId: String
    ): ResponseEntity<DialogSummaryResponse> {
        val callerId = jwt.callerId()
        groupDialogService.removeMember(callerId, dialogId, userId)
        return ResponseEntity.ok(summary(callerId, dialogId))
    }

    @PostMapping("/{dialogId}/leave")
    fun leave(@AuthenticationPrincipal jwt: Jwt, @PathVariable dialogId: String): ResponseEntity<Void> {
        groupDialogService.leave(jwt.callerId(), dialogId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{dialogId}")
    fun delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable dialogId: String): ResponseEntity<Void> {
        groupDialogService.delete(jwt.callerId(), dialogId)
        return ResponseEntity.noContent().build()
    }

    /** Mutations answer with the same shape the list serves, read back after the commit. */
    private fun summary(callerId: String, dialogId: String): DialogSummaryResponse =
        dialogQueryService.metadata(callerId, dialogId)

    /** Without a `sub` there is no user to act as — refuse rather than guess. */
    private fun Jwt.callerId(): String =
        subject ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no subject")
}

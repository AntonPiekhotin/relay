package com.relay.user.input.web

import com.relay.user.model.dto.AddContactRequest
import com.relay.user.model.dto.ContactResponse
import com.relay.user.model.dto.PagedResponse
import com.relay.user.service.ContactService
import com.relay.user.util.DEFAULT_PAGE_SIZE
import com.relay.user.util.userId
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Always "my" contacts: the list is keyed by the JWT's `sub`, and there is no endpoint that reads
 * somebody else's address book. Mounted under `/me` so that stays true by construction rather than
 * by an authorization check that could be forgotten on the next endpoint.
 */
@RestController
@RequestMapping("/api/v1/user/me/contacts")
class ContactController(
    private val contactService: ContactService
) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) size: Int
    ): ResponseEntity<PagedResponse<ContactResponse>> =
        ResponseEntity.ok(contactService.list(jwt.userId(), page, size))

    /** 201 the first time, 200 for a repeat — the body is the same contact either way. */
    @PostMapping
    fun add(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: AddContactRequest
    ): ResponseEntity<ContactResponse> {
        val result = contactService.add(jwt.userId(), request)
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(result.contact)
    }

    @DeleteMapping("/{userId}")
    fun remove(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable userId: String
    ): ResponseEntity<Void> {
        contactService.remove(jwt.userId(), userId)
        return ResponseEntity.noContent().build()
    }
}
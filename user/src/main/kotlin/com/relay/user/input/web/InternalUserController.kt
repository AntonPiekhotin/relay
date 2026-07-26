package com.relay.user.input.web

import com.relay.common.dto.CreateUserRequest
import com.relay.common.dto.UserResponse
import com.relay.user.service.UserService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Service-to-service only. Deliberately under `/internal` rather than `/api/v1/user`, which
 * is the path the api-gateway routes here, so these endpoints are not reachable from outside
 * the cluster. Auth calls [create] during registration, before the user holds any token.
 */
@RestController
@RequestMapping(path = ["/internal/api/v1/users"])
class InternalUserController(
    private val userService: UserService
) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateUserRequest): ResponseEntity<UserResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request))

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<UserResponse> =
        ResponseEntity.ok(userService.getById(id))
}
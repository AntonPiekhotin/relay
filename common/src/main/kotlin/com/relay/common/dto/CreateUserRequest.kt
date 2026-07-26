package com.relay.common.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Contract between auth and user-service: the profile to persist once the identity
 * exists in Keycloak.
 *
 * [id] is the Keycloak user id, i.e. the `sub` claim of every token issued for this
 * user, so user-service can key the profile by it without a second lookup.
 */
data class CreateUserRequest(

    @field:NotBlank
    val id: String,

    @field:Email
    @field:NotBlank
    @field:Size(max = 256)
    val email: String,

    @field:NotBlank
    @field:Size(max = 128)
    val firstName: String,

    @field:NotBlank
    @field:Size(max = 128)
    val lastName: String
)
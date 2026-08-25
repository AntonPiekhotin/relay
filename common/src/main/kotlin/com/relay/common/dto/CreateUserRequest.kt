package com.relay.common.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

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
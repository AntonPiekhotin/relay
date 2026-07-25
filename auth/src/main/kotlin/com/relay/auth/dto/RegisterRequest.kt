package com.relay.auth.dto

import com.relay.auth.util.PasswordConstraint
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.hibernate.validator.constraints.Length

data class RegisterRequest(

    @field:Email
    @field:NotBlank
    @field:Length(max = 256)
    val email: String,
    @field:PasswordConstraint
    val password: String,
    @field:NotBlank
    val firstName: String,
    @field:NotBlank
    val lastName: String
)

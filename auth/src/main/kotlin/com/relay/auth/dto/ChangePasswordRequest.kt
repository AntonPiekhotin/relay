package com.relay.auth.dto

import com.relay.auth.util.PasswordConstraint
import jakarta.validation.constraints.NotBlank

data class ChangePasswordRequest(

    @field:NotBlank
    val currentPassword: String,

    @field:PasswordConstraint
    val newPassword: String
)
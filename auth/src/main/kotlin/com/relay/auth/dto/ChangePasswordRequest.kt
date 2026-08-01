package com.relay.auth.dto

import com.relay.auth.util.PasswordConstraint
import jakarta.validation.constraints.NotBlank

/**
 * Changing your own password. Two things are deliberately absent:
 *  - a user id — it is the JWT's `sub`, so nobody can reset somebody else's credential;
 *  - a "confirm password" field — that is a form concern the client owns.
 *
 * [currentPassword] is required even though the caller already holds a valid token: a stolen access
 * token must not be enough to take over the account permanently, which is exactly what changing the
 * password would do.
 */
data class ChangePasswordRequest(

    @field:NotBlank
    val currentPassword: String,

    @field:PasswordConstraint
    val newPassword: String
)
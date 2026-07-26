package com.relay.common.dto

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.Instant

/**
 * Profile as returned by user-service. Shared so any service that reads a profile
 * deserializes the same shape.
 */
data class UserResponse(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String,

    @field:JsonFormat(shape = JsonFormat.Shape.STRING)
    val createdAt: Instant
)
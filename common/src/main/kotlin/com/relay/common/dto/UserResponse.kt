package com.relay.common.dto

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.Instant

data class UserResponse(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String,

    @field:JsonFormat(shape = JsonFormat.Shape.STRING)
    val createdAt: Instant
)
package com.relay.user.mapper

import com.relay.common.dto.CreateUserRequest
import com.relay.common.dto.UserResponse
import com.relay.user.model.User

fun CreateUserRequest.toEntity() = User(
    id = id,
    email = email,
    firstName = firstName,
    lastName = lastName
)

fun User.toResponse() = UserResponse(
    id = id,
    email = email,
    firstName = firstName,
    lastName = lastName,
    createdAt = createdAt
)
package com.relay.user.mapper

import com.relay.common.dto.CreateUserRequest
import com.relay.common.dto.UserResponse
import com.relay.user.model.Contact
import com.relay.user.model.User
import com.relay.user.model.UserAvatar
import com.relay.user.model.dto.AvatarResponse
import com.relay.user.model.dto.ContactResponse
import com.relay.user.model.dto.ProfileResponse
import com.relay.user.model.dto.UserSummaryResponse

fun CreateUserRequest.toEntity() = User(
    id = id,
    email = email,
    firstName = firstName,
    lastName = lastName
)

/** The cross-service shape from `common`, used by the `/internal` endpoints auth calls. */
fun User.toResponse() = UserResponse(
    id = id,
    email = email,
    firstName = firstName,
    lastName = lastName,
    createdAt = createdAt
)

fun User.toProfile() = ProfileResponse(
    id = id,
    email = email,
    firstName = firstName,
    lastName = lastName,
    avatarUrl = avatarUrl,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun User.toSummary() = UserSummaryResponse(
    id = id,
    email = email,
    firstName = firstName,
    lastName = lastName,
    avatarUrl = avatarUrl
)

fun User.toContactResponse(contact: Contact) = ContactResponse(
    user = toSummary(),
    addedAt = contact.createdAt
)

fun UserAvatar.toResponse(avatarUrl: String) = AvatarResponse(
    avatarUrl = avatarUrl,
    contentType = contentType,
    sizeBytes = sizeBytes,
    updatedAt = updatedAt
)
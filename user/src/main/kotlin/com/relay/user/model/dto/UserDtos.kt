package com.relay.user.model.dto

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * The editable projection of a profile, replaced wholesale by `PUT`. Both fields are required: a
 * PUT that accepted a partial body would be a PATCH under the wrong verb, and the caller could not
 * tell which half of its request had taken effect.
 *
 * Absent from the request on purpose — these are the fields that make the projection smaller than
 * the profile itself:
 *  - `email` — it is the Keycloak username; changing it only here desyncs identity from profile.
 *  - `password` — lives in Keycloak, changed via `POST /api/v1/auth/password`.
 *  - `avatarUrl` — set by uploading a picture, not by naming a URL, or clients could point the
 *    field at anything and we would serve it as this user's face.
 *
 * `@NotBlank` rather than `@Size(min = 1)`: the latter counts `"   "` as a valid name.
 */
data class UpdateProfileRequest(

    @field:NotBlank
    @field:Size(max = 128)
    val firstName: String,

    @field:NotBlank
    @field:Size(max = 128)
    val lastName: String
)

/** The caller's own profile. Only ever returned for the JWT's `sub`. */
data class ProfileResponse(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String?,

    @field:JsonFormat(shape = JsonFormat.Shape.STRING)
    val createdAt: Instant,

    @field:JsonFormat(shape = JsonFormat.Shape.STRING)
    val updatedAt: Instant
)

/** Somebody else, as seen in a search result or a contact list. No timestamps, no last-seen. */
data class UserSummaryResponse(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String?
)

/**
 * [contact] is named without the `is` prefix deliberately: Kotlin's `isContact` compiles to an
 * `isContact()` getter, which Jackson serializes as `"contact"` anyway — so the field name and
 * the JSON key would disagree.
 */
data class UserSearchResultResponse(
    val user: UserSummaryResponse,
    val contact: Boolean
)

data class AvatarResponse(
    val avatarUrl: String,
    val contentType: String,
    val sizeBytes: Int,

    @field:JsonFormat(shape = JsonFormat.Shape.STRING)
    val updatedAt: Instant
)
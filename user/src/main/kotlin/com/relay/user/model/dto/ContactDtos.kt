package com.relay.user.model.dto

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * No ownerId field: the owner is always the JWT's `sub`, so a client cannot edit somebody else's
 * address book (the same rule the notification service applies to device tokens).
 */
data class AddContactRequest(

    @field:NotBlank
    @field:Size(max = 64)
    val userId: String
)

data class ContactResponse(
    val user: UserSummaryResponse,

    @field:JsonFormat(shape = JsonFormat.Shape.STRING)
    val addedAt: Instant
)

/**
 * [created] distinguishes a first add from a repeat, mirroring message-service's send result.
 * Adding twice is not an error — it is what a client that retried a request does — so the
 * response is the same contact either way and only the status code differs.
 */
data class AddContactResult(
    val contact: ContactResponse,
    val created: Boolean
)
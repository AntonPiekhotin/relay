package com.relay.message.model.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * The `/internal` shape: the caller names every participant, including itself. Kept off `common`
 * until something else needs it.
 */
data class CreateDialogRequest(

    @field:NotEmpty
    @field:Size(min = 2, message = "a dialog needs at least two participants")
    val participantIds: Set<String>
)

/**
 * The client-facing shape. Only the other person is named — the caller is the JWT subject, so a
 * client cannot open a conversation on somebody else's behalf.
 */
data class OpenDirectDialogRequest(

    @field:NotBlank
    @field:Size(max = 64, message = "peerId is at most 64 characters")
    val peerId: String
)

data class DialogResponse(
    val id: String,
    val type: String,
    val participantIds: Set<String>,
    val createdAt: Instant
)

/** [created] is false when the dialog already existed — a repeat open is not an error. */
data class OpenDialogResult(val dialog: DialogResponse, val created: Boolean)

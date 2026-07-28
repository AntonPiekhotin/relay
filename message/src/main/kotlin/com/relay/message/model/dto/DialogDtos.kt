package com.relay.message.model.dto

import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Local to message-service: the gateway never creates dialogs, only clients do. Kept off
 * `common` until something else needs the shape.
 */
data class CreateDialogRequest(

    @field:NotEmpty
    @field:Size(min = 2, message = "a dialog needs at least two participants")
    val participantIds: Set<String>
)

data class DialogResponse(
    val id: String,
    val participantIds: Set<String>,
    val createdAt: Instant
)
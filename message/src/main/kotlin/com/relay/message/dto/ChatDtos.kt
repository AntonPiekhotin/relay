package com.relay.message.dto

import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Local to message-service: the gateway never creates chats, only clients do. Kept off `common`
 * until something else needs the shape.
 */
data class CreateChatRequest(

    @field:NotEmpty
    @field:Size(min = 2, message = "a chat needs at least two participants")
    val participantIds: Set<String>
)

data class ChatResponse(
    val id: String,
    val participantIds: Set<String>,
    val createdAt: Instant
)
package com.relay.common.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * REST fallback send (ARCHITECTURE.md §20.2) — used when the socket is not established, by
 * bots, and by tests. Converges on the same persistence code as the Kafka path.
 *
 * [clientMessageId] plus [senderId] is the idempotency key; a retry returns the stored message.
 *
 * [senderId] is trusted, which is why this endpoint must stay unreachable from outside the
 * cluster (`/internal`, not routed by the api-gateway).
 */
data class SendMessageRequest(

    @field:NotBlank
    @field:Size(max = 64)
    val clientMessageId: String,

    @field:NotBlank
    val dialogId: String,

    @field:NotBlank
    val senderId: String,

    @field:NotBlank
    @field:Size(max = 4000)
    val text: String
)
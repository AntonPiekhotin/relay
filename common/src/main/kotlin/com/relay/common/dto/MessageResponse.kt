package com.relay.common.dto

import java.time.Instant

/** A persisted message, as returned by message-service. */
data class MessageResponse(
    val id: String,
    val chatId: String,
    val senderId: String,
    val body: String,
    val sentAt: Instant,
    val clientMessageId: String
)
package com.relay.common.dto

import java.time.Instant

data class MessageResponse(
    val id: String,
    val dialogId: String,
    val senderId: String,
    val text: String,
    val sentAt: Instant,
    val clientMessageId: String
)
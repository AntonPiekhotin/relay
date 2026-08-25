package com.relay.common.event

data class SendMessageCommand(
    val clientMessageId: String,
    val dialogId: String,
    val senderId: String,
    val senderSessionId: String,
    val text: String
)
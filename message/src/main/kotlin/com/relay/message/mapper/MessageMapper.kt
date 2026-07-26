package com.relay.message.mapper

import com.relay.common.dto.MessageResponse
import com.relay.message.dto.ChatResponse
import com.relay.message.model.Chat
import com.relay.message.model.Message

fun Message.toResponse() = MessageResponse(
    id = id.toString(),
    chatId = chatId.toString(),
    senderId = senderId,
    body = body,
    sentAt = sentAt,
    clientMessageId = clientMessageId
)

fun Chat.toResponse() = ChatResponse(
    id = id.toString(),
    participantIds = participantIds.toSet(),
    createdAt = createdAt
)
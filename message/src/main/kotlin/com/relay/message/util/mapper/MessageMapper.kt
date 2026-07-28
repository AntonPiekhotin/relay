package com.relay.message.util.mapper

import com.relay.common.dto.MessageResponse
import com.relay.message.model.dto.DialogResponse
import com.relay.message.model.Dialog
import com.relay.message.model.Message

fun Message.toResponse() = MessageResponse(
    id = id.toString(),
    dialogId = dialogId.toString(),
    senderId = senderId,
    text = text,
    sentAt = sentAt,
    clientMessageId = clientMessageId
)

fun Dialog.toResponse() = DialogResponse(
    id = id.toString(),
    participantIds = participantIds.toSet(),
    createdAt = createdAt
)
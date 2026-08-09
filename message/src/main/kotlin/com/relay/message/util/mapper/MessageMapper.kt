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

/** `type` goes out lowercase to match the wire shape documented in `docs/PROTOCOL.md` §5.3. */
fun Dialog.toResponse() = DialogResponse(
    id = id.toString(),
    type = type.name.lowercase(),
    participantIds = participantIds.toSet(),
    createdAt = createdAt
)
package com.relay.message.service

import com.relay.common.dto.MessageResponse
import com.relay.common.dto.SendMessageRequest
import com.relay.common.exception.RelayException
import com.relay.message.event.MessagePersisted
import com.relay.message.mapper.toResponse
import com.relay.message.model.Message
import com.relay.message.repository.ChatRepository
import com.relay.message.repository.MessageRepository
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** [created] is false when an existing message was returned for a repeated clientMessageId. */
data class SendResult(val message: MessageResponse, val created: Boolean)

@Service
class MessageService(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val events: ApplicationEventPublisher
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun send(request: SendMessageRequest): SendResult {
        val chatId = request.chatId.toUuid("chatId")
        val chat = chatRepository.findById(chatId).orElseThrow {
            RelayException(HttpStatus.NOT_FOUND.value(), "Chat ${request.chatId} not found")
        }
        if (request.senderId !in chat.participantIds) {
            throw RelayException(
                HttpStatus.FORBIDDEN.value(),
                "Sender ${request.senderId} is not a participant of chat ${request.chatId}"
            )
        }

        // The realistic duplicate: the client's send succeeded, its acknowledgement was lost, and
        // it retried over REST. Returning the stored message makes that retry a no-op.
        messageRepository.findByChatIdAndClientMessageId(chatId, request.clientMessageId)?.let {
            logger.debug("Returning existing message {} for repeated clientMessageId", it.id)
            return SendResult(it.toResponse(), created = false)
        }

        val saved = messageRepository.save(
            Message(
                chatId = chatId,
                senderId = request.senderId,
                body = request.body,
                clientMessageId = request.clientMessageId
            )
        )

        // Published only after the transaction commits, so a rolled-back send is never announced.
        // Recipients include the sender: their other devices need the message too, and their
        // originating client reconciles it by clientMessageId.
        events.publishEvent(MessagePersisted(saved, chat.participantIds.toSet()))
        return SendResult(saved.toResponse(), created = true)
    }

    private fun String.toUuid(field: String): UUID =
        try {
            UUID.fromString(this)
        } catch (ex: IllegalArgumentException) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "$field is not a valid id: $this", ex)
        }
}
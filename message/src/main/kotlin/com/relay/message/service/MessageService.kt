package com.relay.message.service

import com.relay.common.dto.MessageResponse
import com.relay.common.dto.SendMessageRequest
import com.relay.common.exception.RelayException
import com.relay.message.model.dto.event.MessagePersisted
import com.relay.message.util.mapper.toResponse
import com.relay.message.model.Message
import com.relay.message.repository.DialogRepository
import com.relay.message.repository.MessageRepository
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The single convergence point for both send paths — the Kafka consumer and the REST fallback
 * both land here, per ARCHITECTURE.md §20.2 ("both paths must converge on the same persistence
 * and delivery code").
 */
@Service
class MessageService(
    private val messageRepository: MessageRepository,
    private val dialogRepository: DialogRepository,
    private val events: ApplicationEventPublisher
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * [senderSessionId] is present only on the WebSocket path; it rides along so the resulting
     * delivery event can route the ack back to the exact device that sent.
     */
    @Transactional
    fun send(request: SendMessageRequest, senderSessionId: String? = null): SendResult {
        val dialogId = request.dialogId.toUuid("dialogId")
        val dialog = dialogRepository.findById(dialogId).orElseThrow {
            RelayException(HttpStatus.NOT_FOUND.value(), "Dialog ${request.dialogId} not found")
        }
        if (request.senderId !in dialog.participantIds) {
            throw RelayException(
                HttpStatus.FORBIDDEN.value(),
                "Sender ${request.senderId} is not a participant of dialog ${request.dialogId}"
            )
        }

        // The realistic duplicate: the send succeeded, its acknowledgement was lost, and the
        // client retried. Returning the stored message makes that retry a no-op (§20.3).
        messageRepository.findBySenderIdAndClientMessageId(request.senderId, request.clientMessageId)?.let {
            logger.debug("Returning existing message {} for repeated clientMessageId", it.id)
            return SendResult(it.toResponse(), dialog.participantIds.toSet(), created = false)
        }

        val saved = messageRepository.saveAndFlush(
            Message(
                dialogId = dialogId,
                senderId = request.senderId,
                text = request.text,
                clientMessageId = request.clientMessageId
            )
        )

        // Published only after the transaction commits, so a rolled-back send is never announced.
        // Recipients include the sender: their other devices need the message too.
        events.publishEvent(MessagePersisted(saved, dialog.participantIds.toSet(), senderSessionId))
        return SendResult(saved.toResponse(), dialog.participantIds.toSet(), created = true)
    }

    private fun String.toUuid(field: String): UUID =
        try {
            UUID.fromString(this)
        } catch (ex: IllegalArgumentException) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "$field is not a valid id: $this", ex)
        }
}

/** [created] is false when an existing message was returned for a repeated clientMessageId. */
data class SendResult(val message: MessageResponse, val recipientIds: Set<String>, val created: Boolean)

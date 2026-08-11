package com.relay.message.service

import com.relay.common.dto.MessageResponse
import com.relay.common.dto.SendMessageRequest
import com.relay.common.exception.RelayException
import com.relay.message.model.dto.event.MessagePersisted
import com.relay.message.util.mapper.toResponse
import com.relay.message.util.toUuidOrBadRequest
import com.relay.message.model.Message
import com.relay.message.repository.DialogRepository
import com.relay.message.repository.MessageRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The single convergence point for both send paths — the Kafka consumer and the REST fallback
 * both land here, so persistence and delivery cannot drift between the two.
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
        val dialogId = request.dialogId.toUuidOrBadRequest("dialogId")
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
        // client retried. Returning the stored message makes that retry a no-op.
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
        val recipientIds = dialog.participantIds.toSet()

        // What the dialog list is ordered by, moved in the same transaction as the insert it
        // describes, so the two cannot disagree: a committed message always has a `last_message_at`
        // at least as recent as itself.
        //
        // Read the guarded UPDATE in the repository before touching this. It also takes a row lock
        // on the dialog for the rest of the transaction, which serializes concurrent sends to the
        // same conversation — harmless here, because `messages.incoming` is keyed by dialogId and one
        // dialog's sends are already handled sequentially by a single consumer thread.
        dialogRepository.touchLastMessageAt(dialogId, saved.sentAt)

        // Published only after the transaction commits, so a rolled-back send is never announced.
        // Recipients include the sender: their other devices need the message too.
        events.publishEvent(MessagePersisted(saved, recipientIds, senderSessionId))
        return SendResult(saved.toResponse(), recipientIds, created = true)
    }
}

/** [created] is false when an existing message was returned for a repeated clientMessageId. */
data class SendResult(val message: MessageResponse, val recipientIds: Set<String>, val created: Boolean)

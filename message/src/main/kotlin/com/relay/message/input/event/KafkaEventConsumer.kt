package com.relay.message.input.event

import com.relay.common.dto.SendMessageRequest
import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageDeliveryEvent
import com.relay.common.event.SendMessageCommand
import com.relay.common.exception.RelayException
import com.relay.message.output.event.KafkaEventProducer
import com.relay.message.util.mapper.toResponse
import com.relay.message.repository.MessageRepository
import com.relay.message.service.MessageService
import com.relay.message.service.SendResult
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * The WebSocket send path (ARCHITECTURE.md §13.1, §20.1): consumes client sends from
 * `messages.incoming`, persists through the same [MessageService] the REST fallback uses, and
 * answers with an Accepted/Rejected event on `messages.delivery`.
 *
 * Every outcome — success, duplicate, rejection — produces a delivery event. A send that
 * produced no event would leave the client's message stuck in "sending" until its timeout.
 *
 * Shared consumer group: message-service instances compete for partitions, which is correct
 * here (each command must be processed once) — unlike the gateway's broadcast groups.
 */
@Component
class KafkaEventConsumer(
    private val messageService: MessageService,
    private val messageRepository: MessageRepository,
    private val eventProducer: KafkaEventProducer,
    private val jsonMapper: JsonMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [KafkaTopics.MESSAGES_INCOMING], groupId = "message-service")
    fun onSendCommand(raw: String) {
        val command = try {
            jsonMapper.readValue(raw, SendMessageCommand::class.java)
        } catch (ex: Exception) {
            logger.error("Skipping malformed send command: {}", raw.take(512), ex)
            return
        }
        try {
            val result = messageService.send(command.toRequest(), command.senderSessionId)
            if (result.alreadyExists()) {
                sendAckDirectly(result, command)
            }
        } catch (ex: RelayException) {
            eventProducer.publish(command.rejected(code(ex.statusCode), ex.message ?: "Send rejected"))
        } catch (ex: DataIntegrityViolationException) {
            handleExistedMessage(command, ex)
        } catch (ex: Exception) {
            logger.error("Send {} failed unexpectedly", command.clientMessageId, ex)
            eventProducer.publish(command.rejected("INTERNAL", "Send failed"))
        }
    }

    private fun SendResult.alreadyExists(): Boolean = !this.created

    /**
     * It means that the original ack for this message was lost. So the ack event
     * must be published here — otherwise the retry never gets its ack (§20.3).
     */
    private fun sendAckDirectly(
        result: SendResult,
        command: SendMessageCommand
    ) {
        eventProducer.publish(
            MessageDeliveryEvent.Accepted(
                messageId = result.message.id,
                dialogId = result.message.dialogId,
                senderId = result.message.senderId,
                senderSessionId = command.senderSessionId,
                text = result.message.text,
                sentAt = result.message.sentAt,
                recipientIds = emptyList(),
                clientMessageId = result.message.clientMessageId,
                duplicate = true
            )
        )
    }

    /**
     * This is a duplicate ack, not a failure. The message was already saved in the database, but ack was lost,
     * so the message is re-read from the database, and the ack is published directly.
     */
    private fun handleExistedMessage(
        command: SendMessageCommand,
        ex: DataIntegrityViolationException
    ) {
        val existing = messageRepository
            .findBySenderIdAndClientMessageId(command.senderId, command.clientMessageId)
        if (existing == null) {
            logger.error("Constraint violation but no stored message for {}", command.clientMessageId, ex)
            eventProducer.publish(command.rejected("INTERNAL", "Send failed"))
            return
        }
        val message = existing.toResponse()
        eventProducer.publish(
            MessageDeliveryEvent.Accepted(
                messageId = message.id,
                dialogId = message.dialogId,
                senderId = message.senderId,
                senderSessionId = command.senderSessionId,
                text = message.text,
                sentAt = message.sentAt,
                recipientIds = emptyList(),
                clientMessageId = message.clientMessageId,
                duplicate = true
            )
        )
    }

    private fun SendMessageCommand.toRequest() = SendMessageRequest(
        clientMessageId = clientMessageId,
        dialogId = dialogId,
        senderId = senderId,
        text = text
    )

    private fun SendMessageCommand.rejected(code: String, reason: String) =
        MessageDeliveryEvent.Rejected(
            clientMessageId = clientMessageId,
            senderId = senderId,
            senderSessionId = senderSessionId,
            code = code,
            reason = reason
        )

    private fun code(status: Int): String = when (status) {
        HttpStatus.NOT_FOUND.value() -> "DIALOG_NOT_FOUND"
        HttpStatus.FORBIDDEN.value() -> "NOT_A_PARTICIPANT"
        HttpStatus.BAD_REQUEST.value() -> "INVALID_REQUEST"
        else -> "INTERNAL"
    }
}
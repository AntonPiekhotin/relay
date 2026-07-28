package com.relay.message.input.event

import com.relay.common.dto.SendMessageRequest
import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageDeliveryEvent
import com.relay.common.event.SendMessageCommand
import com.relay.common.exception.RelayException
import com.relay.message.output.event.MessageEventPublisher
import com.relay.message.util.mapper.toResponse
import com.relay.message.repository.MessageRepository
import com.relay.message.service.MessageService
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
class IncomingMessageConsumer(
    private val messageService: MessageService,
    private val messageRepository: MessageRepository,
    private val publisher: MessageEventPublisher,
    private val jsonMapper: JsonMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [KafkaTopics.MESSAGES_INCOMING], groupId = "message-service")
    fun onSendCommand(raw: String) {
        val command = try {
            jsonMapper.readValue(raw, SendMessageCommand::class.java)
        } catch (ex: Exception) {
            // Poison message: skipping is deliberate — retrying forever would stall the
            // partition and block every well-formed send behind it.
            logger.error("Skipping malformed send command: {}", raw.take(512), ex)
            return
        }
        try {
            val result = messageService.send(command.toRequest(), command.senderSessionId)
            // For a fresh message the Accepted event is published AFTER_COMMIT by
            // MessageEventPublisher. A recognised retry commits nothing, so the ack event
            // must be published here — otherwise the retry never gets its ack (§20.3).
            if (!result.created) {
                publisher.publish(
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
        } catch (ex: RelayException) {
            publisher.publish(command.rejected(code(ex.statusCode), ex.message ?: "Send rejected"))
        } catch (ex: DataIntegrityViolationException) {
            // Two retries of the same send raced; the constraint caught it. The row exists, so
            // this is a duplicate ack, not a failure.
            val existing = messageRepository
                .findBySenderIdAndClientMessageId(command.senderId, command.clientMessageId)
            if (existing == null) {
                logger.error("Constraint violation but no stored message for {}", command.clientMessageId, ex)
                publisher.publish(command.rejected("INTERNAL", "Send failed"))
                return
            }
            val message = existing.toResponse()
            publisher.publish(
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
        } catch (ex: Exception) {
            logger.error("Send {} failed unexpectedly", command.clientMessageId, ex)
            publisher.publish(command.rejected("INTERNAL", "Send failed"))
        }
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
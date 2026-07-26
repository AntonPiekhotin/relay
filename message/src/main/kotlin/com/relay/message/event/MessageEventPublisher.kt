package com.relay.message.event

import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageCreatedEvent
import com.relay.message.model.Message
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import tools.jackson.databind.json.JsonMapper

/** Raised inside the send transaction; published to Kafka only once that transaction commits. */
data class MessagePersisted(val message: Message, val recipientIds: Set<String>)

@Component
class MessageEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jsonMapper: JsonMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * AFTER_COMMIT so a rolled-back send is never announced to the gateway.
     *
     * This is at-most-once: a crash between commit and publish loses the event, and the client
     * recovers it from history on reconnect rather than over the socket. An outbox table would
     * close that gap when it matters.
     *
     * Keyed by chat so a chat's messages stay ordered within a partition.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMessagePersisted(event: MessagePersisted) {
        val message = event.message
        val payload = MessageCreatedEvent(
            id = message.id.toString(),
            chatId = message.chatId.toString(),
            senderId = message.senderId,
            body = message.body,
            sentAt = message.sentAt,
            recipientIds = event.recipientIds.toList(),
            clientMessageId = message.clientMessageId
        )
        kafkaTemplate.send(
            KafkaTopics.MESSAGE_CREATED,
            message.chatId.toString(),
            jsonMapper.writeValueAsString(payload)
        ).whenComplete { _, ex ->
            if (ex != null) {
                logger.error("Message {} was stored but could not be announced", message.id, ex)
            } else {
                logger.debug("Announced message {} to {} recipient(s)", message.id, payload.recipientIds.size)
            }
        }
    }
}
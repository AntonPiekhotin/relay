package com.relay.message.output.event

import com.relay.common.event.KafkaTopics
import com.relay.common.event.MessageDeliveryEvent
import com.relay.common.observability.RequestIdContext
import com.relay.message.model.dto.event.MessagePersisted
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import tools.jackson.databind.json.JsonMapper

@Component
class KafkaEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jsonMapper: JsonMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * AFTER_COMMIT so a rolled-back send is never announced to the gateway.
     *
     * This is at-most-once: a crash between commit and publish loses the event, and the client
     * recovers it from history on reconnect rather than over the socket — the accepted dual-write
     * tradeoff. An outbox table is the escalation path.
     *
     * Keyed by dialog so a dialog's messages stay ordered within a partition.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMessagePersisted(event: MessagePersisted) {
        publish(
            MessageDeliveryEvent.Accepted(
                messageId = event.message.id.toString(),
                dialogId = event.message.dialogId.toString(),
                senderId = event.message.senderId,
                senderSessionId = event.senderSessionId,
                text = event.message.text,
                sentAt = event.message.sentAt,
                recipientIds = event.recipientIds.toList(),
                clientMessageId = event.message.clientMessageId
            )
        )
    }

    fun publish(event: MessageDeliveryEvent) {
        val key = event.getKey()
        // Captured here, restored in the callback: whenComplete runs on Kafka's producer I/O thread,
        // where the MDC is empty, so without this the one record that says a delivery announcement
        // failed would be the only uncorrelated record in the chain.
        val mdc = RequestIdContext.capture()
        kafkaTemplate.send(KafkaTopics.MESSAGES_DELIVERY, key, jsonMapper.writeValueAsString(event))
            .whenComplete { _, ex ->
                mdc.restoring {
                    if (ex != null) {
                        logger.error("Could not announce delivery event for key {}", key, ex)
                    } else {
                        logger.debug("Announced {} for key {}", event::class.simpleName, key)
                    }
                }
            }
    }

    private fun MessageDeliveryEvent.getKey(): String {
        return when (this) {
            is MessageDeliveryEvent.Accepted -> dialogId
            is MessageDeliveryEvent.Rejected -> senderId
            is MessageDeliveryEvent.Read -> dialogId
        }
    }
}
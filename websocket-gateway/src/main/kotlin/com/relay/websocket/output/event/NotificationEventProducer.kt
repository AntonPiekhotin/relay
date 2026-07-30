package com.relay.websocket.output.event

import com.relay.common.event.KafkaTopics
import com.relay.common.event.NotificationRequestedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Owns the gateway's side of the `notifications` contract: one event per offline recipient,
 * keyed by that recipient so notification-service reads one partition per user in order.
 *
 * Fire-and-forget by design: a lost push request degrades to "no notification", and the message
 * itself is safe in the database either way (Principle 1). Nothing here may fail the delivery
 * path that queued it.
 */
@Component
class NotificationEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jsonMapper: JsonMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun publish(request: NotificationRequestedEvent) {
        kafkaTemplate
            .send(KafkaTopics.NOTIFICATIONS, request.recipientId, jsonMapper.writeValueAsString(request))
            .whenComplete { _, ex ->
                if (ex != null) {
                    logger.error(
                        "Could not request a {} notification for offline user {}",
                        request.kind, request.recipientId, ex
                    )
                } else {
                    logger.debug("Requested {} notification for offline user {}", request.kind, request.recipientId)
                }
            }
    }
}
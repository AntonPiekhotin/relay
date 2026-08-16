package com.relay.websocket.output.event

import com.relay.common.event.KafkaTopics
import com.relay.common.event.PresenceEvent
import com.relay.common.event.TypingEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Owns the gateway's side of the `presence.update` and `typing.start` contracts: topics, partition
 * keys, and serialization live here rather than in the presence service.
 *
 * **Two keys, on purpose.** Presence is keyed by the *subject user*, so one person's transitions
 * cannot be reordered against each other. Typing is keyed by *dialog*, matching every other
 * dialog-scoped topic, so one conversation's indicators stay together and different conversations
 * spread across partitions.
 *
 * **Fire-and-forget, and more so than any other producer here.** A lost send request fails a client's
 * message; a lost presence event degrades to a stale dot that the next subscribe snapshot corrects,
 * and a lost typing event to an indicator that never appears. Nothing here may fail a socket
 * operation, so failures are logged and dropped — there is no error frame for either.
 */
@Component
class PresenceEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jsonMapper: JsonMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun publish(event: PresenceEvent) {
        publish(KafkaTopics.PRESENCE_UPDATE, event.userId, event) {
            "presence ${event.status} for user ${event.userId}"
        }
    }

    fun publish(event: TypingEvent) {
        publish(KafkaTopics.TYPING_START, event.dialogId, event) {
            "typing by user ${event.userId} in dialog ${event.dialogId}"
        }
    }

    private fun publish(topic: String, key: String, event: Any, describe: () -> String) {
        kafkaTemplate
            .send(topic, key, jsonMapper.writeValueAsString(event))
            .whenComplete { _, ex ->
                if (ex != null) {
                    logger.warn("Could not publish {}: {}", describe(), ex.message)
                } else {
                    logger.debug("Published {}", describe())
                }
            }
    }
}

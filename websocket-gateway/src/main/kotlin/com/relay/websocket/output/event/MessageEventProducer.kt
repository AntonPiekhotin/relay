package com.relay.websocket.output.event

import com.relay.common.event.KafkaTopics
import com.relay.common.event.MarkReadCommand
import com.relay.common.event.SendMessageCommand
import java.util.concurrent.CompletableFuture
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Owns the gateway's side of the `messages.incoming` and `messages.read` contracts: topics,
 * partition keys, and serialization live here, not in the frame router.
 *
 * Both are keyed by dialog so a conversation's commands stay ordered within one partition.
 */
@Component
class MessageEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jsonMapper: JsonMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Hands a client send to the queue. Completes when the broker acknowledges — exceptionally
     * on a failed hand-off, which is the caller's cue to tell the client to retry over REST.
     * Fire-and-forget beyond that: the ack itself arrives later via `messages.delivery`.
     */
    fun publish(command: SendMessageCommand): CompletableFuture<Void> =
        kafkaTemplate
            .send(KafkaTopics.MESSAGES_INCOMING, command.dialogId, jsonMapper.writeValueAsString(command))
            .handle { _, ex ->
                if (ex != null) {
                    logger.error(
                        "Could not queue send {} from session {}",
                        command.clientMessageId, command.senderSessionId, ex
                    )
                    throw ex
                }
                logger.debug("Queued send {} for dialog {}", command.clientMessageId, command.dialogId)
                null
            }

    fun publishRead(command: MarkReadCommand) {
        kafkaTemplate
            .send(KafkaTopics.MESSAGES_READ, command.dialogId, jsonMapper.writeValueAsString(command))
            .whenComplete { _, ex ->
                if (ex != null) {
                    logger.error(
                        "Could not queue read of dialog {} up to {} from session {}",
                        command.dialogId, command.upToMessageId, command.readerSessionId, ex
                    )
                } else {
                    logger.debug("Queued read of dialog {} up to {}", command.dialogId, command.upToMessageId)
                }
            }
    }
}
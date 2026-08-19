package com.relay.call.output.event

import com.relay.call.model.dto.event.CallNotificationRequested
import com.relay.call.model.dto.event.CallSignalRaised
import com.relay.common.event.KafkaTopics
import com.relay.common.observability.RequestIdContext
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
     * AFTER_COMMIT so a state change that rolled back is never signalled — a lost optimistic-lock
     * race on `accept` must not put an answer on the wire.
     *
     * `fallbackExecution = true` because not every signal has a transaction behind it: relaying an
     * ICE candidate writes nothing, and the read-only path that resolves its route may not open one
     * at all. Without the flag those events would be silently discarded.
     *
     * Keyed by callId so one call's signals stay ordered within a partition, for the same reason
     * message keys by dialogId.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onSignalRaised(event: CallSignalRaised) {
        val signal = event.signal
        send(KafkaTopics.CALL_SIGNAL, signal.callId, signal)
    }

    /** Push requests for a callee who could not be rung on a socket, or who never answered. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onNotificationRequested(event: CallNotificationRequested) {
        send(KafkaTopics.NOTIFICATIONS, event.request.recipientId, event.request)
    }

    private fun send(topic: String, key: String, payload: Any) {
        // Captured here, restored in the callback: whenComplete runs on Kafka's producer I/O thread,
        // where the MDC is empty, so the record reporting a failed publish would otherwise be the
        // only uncorrelated one in the chain.
        val mdc = RequestIdContext.capture()
        kafkaTemplate.send(topic, key, jsonMapper.writeValueAsString(payload))
            .whenComplete { _, ex ->
                mdc.restoring {
                    if (ex != null) {
                        logger.error("Could not publish to {} for key {}", topic, key, ex)
                    } else {
                        logger.debug("Published {} to {} for key {}", payload::class.simpleName, topic, key)
                    }
                }
            }
    }
}

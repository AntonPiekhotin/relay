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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onSignalRaised(event: CallSignalRaised) {
        val signal = event.signal
        send(KafkaTopics.CALL_SIGNAL, signal.callId, signal)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onNotificationRequested(event: CallNotificationRequested) {
        send(KafkaTopics.NOTIFICATIONS, event.request.recipientId, event.request)
    }

    private fun send(topic: String, key: String, payload: Any) {
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

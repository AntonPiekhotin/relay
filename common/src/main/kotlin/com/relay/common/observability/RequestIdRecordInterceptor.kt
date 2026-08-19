package com.relay.common.observability

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.listener.RecordInterceptor

/**
 * Restores the correlation context around every `@KafkaListener` invocation, from the record header
 * [RequestIdProducerInterceptor] wrote.
 *
 * An id is generated when the header is absent, so a listener always logs under *some* id — a
 * record consumed from an external producer starts its own chain rather than being uncorrelated.
 *
 * Clearing in [afterRecord] is not optional here, unlike on the HTTP path. A listener container's
 * poll loop is one long-lived thread reused for every record, so without the clear, record N+1
 * would silently inherit record N's id — which is worse than no correlation at all, because it
 * looks correct.
 */
class RequestIdRecordInterceptor : RecordInterceptor<Any, Any> {

    override fun intercept(record: ConsumerRecord<Any, Any>, consumer: Consumer<Any, Any>): ConsumerRecord<Any, Any> {
        try {
            val header = record.headers().lastHeader(RequestId.HEADER)
            val id = header?.value()?.decodeToString()?.takeIf { it.isNotBlank() } ?: RequestId.newId()
            RequestIdContext.put(RequestId.MDC_REQUEST_ID, id)
            RequestIdContext.put(RequestId.MDC_KAFKA_TOPIC, record.topic())
        } catch (_: Exception) {
            // Returning the record regardless: dropping it over a log field would lose a message.
        }
        return record
    }

    override fun afterRecord(record: ConsumerRecord<Any, Any>, consumer: Consumer<Any, Any>) {
        RequestIdContext.clear()
    }
}

package com.relay.common.observability

import org.apache.kafka.clients.producer.ProducerInterceptor
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata

/**
 * Stamps the in-scope correlation id onto every outgoing Kafka record as a **record header**.
 *
 * A header rather than a payload field on purpose: the event DTOs in `com.relay.common.event` are
 * a contract, and headers sit outside the JSON body the mapper round-trips — so no DTO changes, no
 * consumer signature changes, and an older consumer that ignores the header still deserializes the
 * record exactly as before.
 *
 * `onSend` is invoked on the thread that called `send`, inside `KafkaTemplate.doSend`, so the MDC
 * is still the caller's. (The `whenComplete` callback on the returned future is *not* — that runs
 * on Kafka's producer I/O thread and needs `RequestIdContext.wrap`.)
 */
class RequestIdProducerInterceptor : ProducerInterceptor<Any?, Any?> {

    override fun onSend(record: ProducerRecord<Any?, Any?>): ProducerRecord<Any?, Any?> {
        try {
            val id = RequestId.current() ?: return record
            if (record.headers().lastHeader(RequestId.HEADER) == null) {
                record.headers().add(RequestId.HEADER, id.toByteArray())
            }
        } catch (_: Exception) {
            // Never fail a send for a log field. A record without the header still gets an id at
            // the consumer, it just starts a new chain there.
        }
        return record
    }

    override fun onAcknowledgement(metadata: RecordMetadata?, exception: Exception?) = Unit

    override fun close() = Unit

    override fun configure(configs: MutableMap<String, *>?) = Unit
}

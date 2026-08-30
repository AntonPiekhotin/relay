package com.relay.common.observability

import java.util.UUID
import org.slf4j.MDC

/**
 * The correlation identifier that ties one request's log records together across services.
 *
 * It travels as the [HEADER] on HTTP hops and as a Kafka record header on event hops.
 */
object RequestId {
    const val HEADER: String = "X-Request-Id"
    const val MDC_REQUEST_ID: String = "requestId"
    const val MDC_USER_ID: String = "userId"
    const val MDC_SESSION_ID: String = "sessionId"
    const val MDC_KAFKA_TOPIC: String = "kafkaTopic"

    fun newId(): String = java.lang.Long.toHexString(UUID.randomUUID().mostSignificantBits)

    fun current(): String? = MDC.get(MDC_REQUEST_ID)

    fun currentOrNew(): String = current() ?: newId()
}

package com.relay.common.observability

import org.slf4j.MDC

/**
 * The correlation identifier that ties one request's log records together across services.
 *
 * It travels as the [HEADER] on HTTP hops and as a Kafka record header on event hops — never as a
 * field on an event DTO or a WebSocket frame, both of which are contracts shared with mobile
 * clients and must not change shape.
 *
 * The id is read from an inbound header when one is present and only generated when it is absent,
 * which is what lets a future nginx front take over minting it with no code change here.
 */
object RequestId {

    /** Not hop-by-hop, so proxies (including Gateway MVC) forward it untouched. */
    const val HEADER: String = "X-Request-Id"

    const val MDC_REQUEST_ID: String = "requestId"
    const val MDC_USER_ID: String = "userId"
    const val MDC_SESSION_ID: String = "sessionId"
    const val MDC_KAFKA_TOPIC: String = "kafkaTopic"

    /**
     * 16 hex characters. Shorter than a UUID's 36 and still far beyond collision range at the
     * volumes one deployment logs, which matters because this string is repeated on every record.
     */
    fun newId(): String = java.lang.Long.toHexString(java.util.UUID.randomUUID().mostSignificantBits)

    /** The id in scope on this thread, or null outside any correlated work. */
    fun current(): String? = MDC.get(MDC_REQUEST_ID)

    /** The id in scope, or a fresh one — for entry points that must always have one. */
    fun currentOrNew(): String = current() ?: newId()
}

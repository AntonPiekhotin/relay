package com.relay.common.event

import java.time.Instant

/**
 * Published by notification-service.
 *
 * [payload] is intentionally untyped: its shape varies by [kind], and the gateway only relays it
 * without interpreting it. Clients switch on [kind] to read the payload.
 */
data class NotificationCreatedEvent(
    val id: String,
    val kind: String,
    val payload: Map<String, Any?> = emptyMap(),
    val createdAt: Instant,
    val recipientIds: List<String>
)
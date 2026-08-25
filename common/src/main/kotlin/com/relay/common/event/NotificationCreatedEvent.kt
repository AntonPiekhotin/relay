package com.relay.common.event

import java.time.Instant

data class NotificationCreatedEvent(
    val id: String,
    val kind: String,
    val payload: Map<String, Any?> = emptyMap(),
    val createdAt: Instant,
    val recipientIds: List<String>
)
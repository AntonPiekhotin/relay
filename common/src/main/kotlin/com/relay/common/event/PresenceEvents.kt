package com.relay.common.event

import java.time.Instant

data class PresenceEvent(
    val userId: String,
    val status: String,
    val lastSeen: Instant? = null
)

object PresenceStatuses {
    const val ONLINE = "online"
    const val OFFLINE = "offline"
}

data class TypingEvent(
    val dialogId: String,
    val userId: String,
    val recipientIds: List<String>
)

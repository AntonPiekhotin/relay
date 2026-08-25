package com.relay.common.event

data class CallSignalEvent(
    val callId: String,
    val fromUserId: String,
    val signal: Map<String, Any?>,
    val recipientIds: List<String>,
    val excludeSessionIds: List<String> = emptyList()
)
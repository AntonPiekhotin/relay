package com.relay.common.dto

data class DialogParticipantsResponse(
    val dialogId: String,
    val participantIds: List<String>
)

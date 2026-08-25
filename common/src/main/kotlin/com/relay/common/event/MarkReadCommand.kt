package com.relay.common.event

data class MarkReadCommand(
    val dialogId: String,
    val readerId: String,
    val readerSessionId: String,
    val upToMessageId: String
)

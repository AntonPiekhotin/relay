package com.relay.message.repository

import com.relay.message.model.Message
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MessageRepository : JpaRepository<Message, UUID> {

    /** Lookup on the idempotency key — the client owns its UUID space. */
    fun findBySenderIdAndClientMessageId(senderId: String, clientMessageId: String): Message?
}
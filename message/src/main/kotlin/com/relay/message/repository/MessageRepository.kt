package com.relay.message.repository

import com.relay.message.model.Message
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MessageRepository : JpaRepository<Message, UUID> {

    fun findByChatIdAndClientMessageId(chatId: UUID, clientMessageId: String): Message?
}
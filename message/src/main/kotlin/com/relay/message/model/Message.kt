package com.relay.message.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * The unique constraint on (chat_id, client_message_id) is what makes sending idempotent, and it
 * is the reason a client's REST fallback after a lost WebSocket ack cannot produce a duplicate.
 * It is enforced in the schema rather than only in code, because two concurrent sends would
 * otherwise both pass an application-level check.
 */
@Entity
@Table(
    name = "messages",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_messages_chat_client_id", columnNames = ["chat_id", "client_message_id"])
    ],
    indexes = [Index(name = "ix_messages_chat_sent_at", columnList = "chat_id, sent_at")]
)
class Message(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "chat_id", nullable = false, updatable = false)
    val chatId: UUID,

    @Column(name = "sender_id", nullable = false, updatable = false, length = 64)
    val senderId: String,

    @Column(name = "body", nullable = false, length = 4000)
    val body: String,

    @Column(name = "client_message_id", nullable = false, updatable = false, length = 64)
    val clientMessageId: String,

    @Column(name = "sent_at", nullable = false, updatable = false)
    val sentAt: Instant = Instant.now()
)
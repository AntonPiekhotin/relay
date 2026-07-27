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
 * The unique constraint on (sender_id, client_message_id) is what makes sending idempotent
 * (ARCHITECTURE.md §19.2): the client owns its UUID space, so a retry of the same send — over
 * the socket or via the REST fallback after a lost ack — cannot produce a second row. It is
 * enforced in the schema rather than only in code, because two concurrent retries would both
 * pass an application-level check.
 */
@Entity
@Table(
    name = "messages",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_messages_sender_client_id", columnNames = ["sender_id", "client_message_id"])
    ],
    indexes = [Index(name = "ix_messages_dialog_sent_at", columnList = "dialog_id, sent_at")]
)
class Message(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "dialog_id", nullable = false, updatable = false)
    val dialogId: UUID,

    @Column(name = "sender_id", nullable = false, updatable = false, length = 64)
    val senderId: String,

    @Column(name = "text", nullable = false, length = 4000)
    val text: String,

    @Column(name = "client_message_id", nullable = false, updatable = false, length = 64)
    val clientMessageId: String,

    @Column(name = "sent_at", nullable = false, updatable = false)
    val sentAt: Instant = Instant.now()
)
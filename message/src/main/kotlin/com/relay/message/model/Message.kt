package com.relay.message.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [USER] is what people send; everything else is a membership system message, a row in the same
 * table on purpose — it orders, pages, and counts with the `(dialog_id, sent_at, id)` index like
 * any message, so history needs no second query and unread needs no change. `GROUP_DELETED` is
 * deliberately absent: deleting a group deletes its messages, so there is no row to be.
 */
enum class MessageKind { USER, GROUP_CREATED, MEMBER_ADDED, MEMBER_REMOVED, MEMBER_LEFT, GROUP_RENAMED }

/**
 * The unique constraint on (sender_id, client_message_id) is what makes sending idempotent
 *: the client owns its UUID space, so a retry of the same send — over
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
    indexes = [Index(name = "ix_messages_dialog_sent_at_id", columnList = "dialog_id, sent_at desc, id desc")]
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
    val sentAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 32)
    val kind: MessageKind = MessageKind.USER,

    /**
     * The member a membership system message is about. Null for [MessageKind.USER],
     * [MessageKind.GROUP_CREATED] and [MessageKind.GROUP_RENAMED]; equal to [senderId] for
     * [MessageKind.MEMBER_LEFT]. An id, never a name — clients resolve it through user-service.
     */
    @Column(name = "target_user_id", updatable = false, length = 64)
    val targetUserId: String? = null
)
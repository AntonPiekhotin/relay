package com.relay.message.repository

import com.relay.message.model.Message
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MessageRepository : JpaRepository<Message, UUID> {

    /** Lookup on the idempotency key — the client owns its UUID space. */
    fun findBySenderIdAndClientMessageId(senderId: String, clientMessageId: String): Message?

    /**
     * Resolves a cursor. Clients pass a **message id** as `before` / `after` / `up_to_message_id`
     * because that is what they already hold, while every keyset query compares `(sent_at, id)` —
     * so the position has to be looked up, and this is that lookup.
     *
     * Scoped to the dialog rather than by id alone: a cursor naming a message in a *different*
     * conversation is a bug or a probe, and resolving it would silently page from a position that
     * does not exist in the dialog being read.
     */
    fun findByIdAndDialogId(id: UUID, dialogId: UUID): Message?
}
package com.relay.message.repository

import com.relay.message.model.Dialog
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface DialogRepository : JpaRepository<Dialog, UUID> {

    /** Single-row by construction: `uk_dialogs_direct_key` is what stops a pair having two dialogs. */
    fun findByDirectKey(directKey: String): Dialog?

    /**
     * Moves `last_message_at` forward, never backward, in one statement.
     *
     * The guard is in the `where` clause rather than in Kotlin on purpose. Two sends to the same
     * dialog read the row at the start of their own transaction, so an application-level
     * `if (lastMessageAt < sentAt)` compares against a value that may already be stale — and the
     * transaction carrying the *older* message could commit second and drag the timestamp back.
     * Letting the database compare makes the column monotonic whatever the interleaving. Same
     * instinct as every other invariant here: if concurrency can break it, the database decides.
     *
     * Returns 0 when the stored timestamp was already at least this recent, which is not an error.
     *
     * `flushAutomatically` so an insert pending in the same transaction is written first;
     * deliberately no `clearAutomatically`, because the caller still reads the `Dialog` it loaded
     * and detaching it would cost a reload. The in-memory copy's `lastMessageAt` goes stale, which
     * is why nothing reads it after this call.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        """
        update Dialog d set d.lastMessageAt = :sentAt
        where d.id = :dialogId
          and (d.lastMessageAt is null or d.lastMessageAt < :sentAt)
        """
    )
    fun touchLastMessageAt(@Param("dialogId") dialogId: UUID, @Param("sentAt") sentAt: Instant): Int
}

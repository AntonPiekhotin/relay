package com.relay.message.repository

import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * The per-user read cursor. Native SQL rather than an entity, because both operations it needs are
 * things JPA cannot express: a monotonic upsert, and — in [MessageQueryRepository] — a row-value
 * comparison against the stored position. An entity mapped over this table would exist only to be
 * bypassed.
 */
@Repository
class ReadStateRepository(
    private val jdbc: JdbcClient
) {

    /**
     * Moves [userId]'s cursor in [dialogId] to `(lastReadAt, lastReadId)`. Returns true when it
     * actually moved.
     *
     * One statement, and the whole correctness argument is in it. `on conflict` makes it an upsert,
     * so first read and hundredth read are the same code path. The `where` on the update is what
     * makes it **monotonic**: read commands are fire-and-forget over Kafka, a client retries freely,
     * and two devices of the same user read at the same moment — so a command carrying an older
     * position will arrive, and it must not drag the cursor backwards and resurrect unread messages
     * the user has already seen. The database compares, for the same reason it decides every other
     * invariant here (CLAUDE.md invariants 6, 11, 12).
     *
     * The row value `(last_read_at, last_read_id) < (excluded...)` rather than the timestamp alone:
     * two messages sent in the same millisecond are distinguishable only by id, and comparing on the
     * timestamp would refuse to advance past the first of them.
     *
     * `false` means the cursor was already at least this far — a no-op, not a failure. The caller
     * uses it to skip the receipt, so a retried read cannot make a read tick fire twice.
     */
    fun advance(dialogId: UUID, userId: String, lastReadAt: Instant, lastReadId: UUID): Boolean =
        jdbc.sql(
            """
            insert into dialog_read_state (dialog_id, user_id, last_read_at, last_read_id, updated_at)
            values (:dialogId, :userId, :lastReadAt, :lastReadId, :now)
            on conflict (dialog_id, user_id) do update
                set last_read_at = excluded.last_read_at,
                    last_read_id = excluded.last_read_id,
                    updated_at   = excluded.updated_at
                where (dialog_read_state.last_read_at, dialog_read_state.last_read_id)
                    < (excluded.last_read_at, excluded.last_read_id)
            """
        )
            .param("dialogId", dialogId)
            .param("userId", userId)
            .param("lastReadAt", lastReadAt.atOffset(ZoneOffset.UTC))
            .param("lastReadId", lastReadId)
            .param("now", Instant.now().atOffset(ZoneOffset.UTC))
            .update() == 1
}

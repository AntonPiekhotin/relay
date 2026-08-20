package com.relay.message.repository

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * One page of history, before mapping. [clientMessageId] is redacted per-caller further up — it
 * belongs to whoever sent the message and means nothing to anybody else.
 */
data class MessageRow(
    val id: UUID,
    val dialogId: UUID,
    val senderId: String,
    val text: String,
    val sentAt: Instant,
    val clientMessageId: String,
    val kind: String,
    val targetUserId: String?
)

/**
 * The read side of `messages`, on [JdbcClient] rather than JPA.
 *
 * Two reasons, both from `docs/DATA.md` §7 and §9. The first is the cursor: correct keyset
 * pagination compares `(sent_at, id)` as a **row value**, and JPQL has no row-value comparison —
 * the equivalent `sent_at < ? or (sent_at = ? and id < ?)` is a filter Postgres applies after
 * scanning rather than a bound on the index range. The second is that history is read-only: there
 * is nothing for a persistence context to track, and hydrating managed entities for a page a client
 * will never write back is work with no purpose.
 *
 * Writes stay on JPA. This is the "drop to JdbcClient for that query" escape hatch, not a migration.
 */
@Repository
class MessageQueryRepository(
    private val jdbc: JdbcClient
) {

    /**
     * The page ending just before `(beforeSentAt, beforeId)`, newest first — the backwards scroll,
     * and with the sentinel cursor also the first page.
     *
     * Both comparisons are strict, so a cursor is exclusive of the message it names: a client passes
     * the oldest row it already holds and gets what precedes it, with no repeated row at the seam.
     */
    fun findPageBefore(dialogId: UUID, beforeSentAt: Instant, beforeId: UUID, limit: Int): List<MessageRow> =
        jdbc.sql(
            """
            select id, dialog_id, sender_id, text, sent_at, client_message_id, kind, target_user_id
            from messages
            where dialog_id = :dialogId
              and (sent_at, id) < (:beforeSentAt, :beforeId)
            order by sent_at desc, id desc
            limit :limit
            """
        )
            .param("dialogId", dialogId)
            .param("beforeSentAt", beforeSentAt.atOffset(ZoneOffset.UTC))
            .param("beforeId", beforeId)
            .param("limit", limit)
            .query(::toMessageRow)
            .list()

    /**
     * The page starting just after `(afterSentAt, afterId)`, oldest first — catch-up after a
     * reconnect, which is the mechanism the whole delivery design leans on: the socket is allowed to
     * be lossy because this query recovers whatever it dropped (`docs/PROTOCOL.md` §7).
     *
     * Ascending, unlike [findPageBefore], so a client walking forward from its last known message
     * applies the gap in the order it happened.
     */
    fun findPageAfter(dialogId: UUID, afterSentAt: Instant, afterId: UUID, limit: Int): List<MessageRow> =
        jdbc.sql(
            """
            select id, dialog_id, sender_id, text, sent_at, client_message_id, kind, target_user_id
            from messages
            where dialog_id = :dialogId
              and (sent_at, id) > (:afterSentAt, :afterId)
            order by sent_at asc, id asc
            limit :limit
            """
        )
            .param("dialogId", dialogId)
            .param("afterSentAt", afterSentAt.atOffset(ZoneOffset.UTC))
            .param("afterId", afterId)
            .param("limit", limit)
            .query(::toMessageRow)
            .list()

    /**
     * How many messages in each of [dialogIds] sit past [userId]'s read cursor, keyed by dialog.
     *
     * One grouped query for the whole dialog list rather than a count per dialog — the list is
     * fetched on every cold start, and a count per row is the N+1 that turns a home screen into a
     * hundred round trips.
     *
     * A dialog with nothing unread is **absent from the result**, not zero. Callers default to 0.
     *
     * `sender_id <> userId` because your own messages are not unread to you, and the comparison is
     * a row value for the same reason the cursors are: `last_read_at` alone cannot separate two
     * messages sent in the same millisecond, so counting on it would leave a chat stuck at 1 unread.
     * A missing read-state row means nothing has been read, so everything counts.
     */
    fun countUnreadByDialog(userId: String, dialogIds: Collection<UUID>): Map<UUID, Long> {
        if (dialogIds.isEmpty()) return emptyMap()
        return jdbc.sql(
            """
            select m.dialog_id, count(*) as unread
            from messages m
            left join dialog_read_state r
                on r.dialog_id = m.dialog_id and r.user_id = :userId
            where m.dialog_id in (:dialogIds)
              and m.sender_id <> :userId
              and (r.last_read_at is null or (m.sent_at, m.id) > (r.last_read_at, r.last_read_id))
            group by m.dialog_id
            """
        )
            .param("userId", userId)
            .param("dialogIds", dialogIds)
            .query { rs, _ -> rs.getObject("dialog_id", UUID::class.java) to rs.getLong("unread") }
            .list()
            .toMap()
    }

    private fun toMessageRow(rs: ResultSet, rowNum: Int): MessageRow = MessageRow(
        id = rs.getObject("id", UUID::class.java),
        dialogId = rs.getObject("dialog_id", UUID::class.java),
        senderId = rs.getString("sender_id"),
        text = rs.getString("text"),
        sentAt = rs.getObject("sent_at", OffsetDateTime::class.java).toInstant(),
        clientMessageId = rs.getString("client_message_id"),
        kind = rs.getString("kind"),
        targetUserId = rs.getString("target_user_id")
    )
}

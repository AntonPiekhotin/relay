package com.relay.message.repository

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** A dialog as the list needs it — membership and unread count are joined on separately. */
data class DialogRow(
    val id: UUID,
    val type: String,
    val title: String?,
    val ownerId: String?,
    val createdAt: Instant,
    val lastMessageAt: Instant?
)

/**
 * The dialog list, on [JdbcClient] for one reason: `Dialog.participantIds` is an eager
 * `@ElementCollection`, so loading N dialogs as entities issues one extra select per dialog to fetch
 * its members. That is the right trade for the send path, which loads exactly one dialog and needs
 * its members immediately — and the wrong one for a list. Here membership comes back in a single
 * second query instead.
 */
@Repository
class DialogQueryRepository(
    private val jdbc: JdbcClient
) {

    /**
     * One page of [userId]'s dialogs, most recently active first — the cursor `docs/DATA.md` §4.3
     * promised the list would need when group dialogs landed.
     *
     * The ordering is the one the list has always had — `last_message_at desc nulls last,
     * created_at desc, id desc` — rewritten as a `coalesce`, which is the same order ("never used"
     * sorts below every real timestamp) but admits a single three-column row-value comparison for
     * the keyset. A `nulls last` sort cannot be keyset-paginated without a null-branch in the
     * predicate; the coalesce form can, and `docs/DATA.md` §7's argument for row values over
     * timestamp-only cursors applies here unchanged.
     *
     * The null sentinel is [NEVER], a concrete year-one timestamp, rather than Postgres's
     * `-infinity`: the *same* value has to appear on both sides of the comparison — in the query
     * for the row and in the bind for a cursor whose own `last_message_at` is null — and a JDBC
     * parameter cannot carry `-infinity` portably. Any real timestamp is after year one, so the
     * order is unchanged.
     *
     * The first page passes sentinel values above any real row, the `MessageHistoryService`
     * pattern, so one query serves both cases. The comparison is strict — a cursor is exclusive of
     * the dialog it names. [cursorLastMessageAt] is nullable because the cursor dialog's own
     * `last_message_at` may be null; the coalesce here is what keeps both sides consistent.
     */
    fun findPageForUser(
        userId: String,
        cursorLastMessageAt: Instant?,
        cursorCreatedAt: Instant,
        cursorId: UUID,
        limit: Int
    ): List<DialogRow> =
        jdbc.sql(
            """
            select d.id, d.type, d.title, d.owner_id, d.created_at, d.last_message_at
            from dialogs d
            join dialog_participants p on p.dialog_id = d.id and p.user_id = :userId
            where (coalesce(d.last_message_at, :never), d.created_at, d.id)
                < (:cursorLastMessageAt, :cursorCreatedAt, :cursorId)
            order by coalesce(d.last_message_at, :never) desc, d.created_at desc, d.id desc
            limit :limit
            """
        )
            .param("userId", userId)
            .param("never", NEVER.atOffset(ZoneOffset.UTC))
            .param("cursorLastMessageAt", (cursorLastMessageAt ?: NEVER).atOffset(ZoneOffset.UTC))
            .param("cursorCreatedAt", cursorCreatedAt.atOffset(ZoneOffset.UTC))
            .param("cursorId", cursorId)
            .param("limit", limit)
            .query { rs, _ ->
                DialogRow(
                    id = rs.getObject("id", UUID::class.java),
                    type = rs.getString("type"),
                    title = rs.getString("title"),
                    ownerId = rs.getString("owner_id"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    lastMessageAt = rs.getObject("last_message_at", OffsetDateTime::class.java)?.toInstant()
                )
            }
            .list()

    /** Membership for a whole page of dialogs in one round trip, keyed by dialog. */
    fun findParticipantsByDialog(dialogIds: Collection<UUID>): Map<UUID, List<String>> {
        if (dialogIds.isEmpty()) return emptyMap()
        return jdbc.sql("select dialog_id, user_id from dialog_participants where dialog_id in (:dialogIds)")
            .param("dialogIds", dialogIds)
            .query { rs, _ -> rs.getObject("dialog_id", UUID::class.java) to rs.getString("user_id") }
            .list()
            .groupBy({ it.first }, { it.second })
    }

    companion object {
        /** The null-`last_message_at` sentinel — see [findPageForUser]. Before any real timestamp. */
        private val NEVER: Instant = Instant.parse("0001-01-01T00:00:00Z")
    }
}

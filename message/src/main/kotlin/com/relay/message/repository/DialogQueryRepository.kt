package com.relay.message.repository

import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** A dialog as the list needs it — membership and unread count are joined on separately. */
data class DialogRow(
    val id: UUID,
    val type: String,
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
     * Every dialog [userId] participates in, most recently active first.
     *
     * Unpaginated, matching the contract in `docs/PROTOCOL.md` §5.1. A direct dialog exists only
     * where somebody opened a conversation, so this is bounded by how many people the user talks to;
     * it is the group-dialog and archive features that would make a cursor necessary, and neither
     * exists. Revisit before either does.
     *
     * `nulls last` so a dialog that was opened and never used sorts to the bottom rather than the
     * top — a null `last_message_at` means "never", and Postgres treats nulls as largest by default
     * on a descending sort. `created_at, id` behind it keeps the order total, so two dialogs whose
     * last message landed in the same millisecond do not swap places between requests.
     */
    fun findAllForUser(userId: String): List<DialogRow> =
        jdbc.sql(
            """
            select d.id, d.type, d.created_at, d.last_message_at
            from dialogs d
            join dialog_participants p on p.dialog_id = d.id and p.user_id = :userId
            order by d.last_message_at desc nulls last, d.created_at desc, d.id desc
            """
        )
            .param("userId", userId)
            .query { rs, _ ->
                DialogRow(
                    id = rs.getObject("id", UUID::class.java),
                    type = rs.getString("type"),
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
}

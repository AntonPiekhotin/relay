package com.relay.message.service

import com.relay.common.exception.RelayException
import com.relay.message.config.MessageProperties
import com.relay.message.model.Dialog
import com.relay.message.model.dto.DialogListResponse
import com.relay.message.model.dto.DialogSummaryResponse
import com.relay.message.model.dto.ReadStateEntry
import com.relay.message.model.dto.ReadStateResponse
import com.relay.message.repository.DialogQueryRepository
import com.relay.message.repository.DialogRepository
import com.relay.message.repository.MessageQueryRepository
import com.relay.message.repository.ReadStateRepository
import com.relay.message.util.toUuidOrBadRequest
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The read side of dialogs: the list that makes a conversation discoverable, and the single-dialog
 * lookup. Also the one place that answers "may this caller read this dialog", which the history and
 * read-state paths both need.
 *
 * Separate from [DialogService] rather than more methods on it. [DialogService] is deliberately
 * **not** transactional — its unique-violation recovery depends on each repository call taking its
 * own transaction — and hanging `@Transactional(readOnly = true)` methods off the same class would
 * put a trap next to that reasoning for whoever edits it next.
 */
@Service
class DialogQueryService(
    private val dialogRepository: DialogRepository,
    private val dialogQueryRepository: DialogQueryRepository,
    private val messageQueryRepository: MessageQueryRepository,
    private val readStateRepository: ReadStateRepository,
    private val properties: MessageProperties
) {

    /**
     * One page of the caller's dialogs, most recently active first, each with the caller's own
     * unread count.
     *
     * This is what closes the discovery gap: before it, a conversation existed only for whoever
     * opened it. If Alice opened a dialog with Bob and sent while Bob was offline, Bob got a push and
     * then had nothing to fetch — the dialog id lived only on Alice's device, so the conversation was
     * invisible until Bob happened to be connected for a live `message.new`.
     *
     * Paginated since group dialogs landed — the cursor `docs/DATA.md` §4.3 said they would force.
     * [cursor] is a dialog id from a previous page, the same "an id you already hold" contract as
     * message history; one that does not resolve to a dialog of the caller's is a **400**, because
     * a broken cursor silently treated as "no cursor" looks like the list jumping to the top rather
     * than like the bug it is. No cursor is the first page.
     *
     * Three queries regardless of how many dialogs come back: the dialogs, their membership, and one
     * grouped unread count. Not one plus three per row.
     */
    @Transactional(readOnly = true)
    fun list(callerId: String, cursor: String? = null, limit: Int? = null): DialogListResponse {
        val pageSize = (limit ?: properties.dialogPageSize).coerceIn(1, properties.maxDialogPageSize)
        val position = cursor?.let { resolveCursor(callerId, it) }

        // A cursor dialog with a null lastMessageAt is a real position ("never used" sorts at the
        // bottom), not the first page — which is why the null-vs-sentinel split is on `position`,
        // never on `position.lastMessageAt`. The repository coalesces the null.
        val dialogs = if (position != null) {
            dialogQueryRepository.findPageForUser(
                userId = callerId,
                cursorLastMessageAt = position.lastMessageAt,
                cursorCreatedAt = position.createdAt,
                cursorId = position.id,
                limit = pageSize
            )
        } else {
            dialogQueryRepository.findPageForUser(
                userId = callerId,
                cursorLastMessageAt = AFTER_ANY_DIALOG,
                cursorCreatedAt = AFTER_ANY_DIALOG,
                cursorId = HIGHEST_UUID,
                limit = pageSize
            )
        }
        if (dialogs.isEmpty()) return DialogListResponse(emptyList())

        val ids = dialogs.map { it.id }
        val participants = dialogQueryRepository.findParticipantsByDialog(ids)
        val unread = messageQueryRepository.countUnreadByDialog(callerId, ids)

        return DialogListResponse(
            dialogs = dialogs.map { row ->
                DialogSummaryResponse(
                    dialogId = row.id.toString(),
                    type = row.type.lowercase(),
                    participantIds = participants[row.id].orEmpty(),
                    lastMessageAt = row.lastMessageAt,
                    unreadCount = unread[row.id] ?: 0L,
                    createdAt = row.createdAt,
                    title = row.title,
                    ownerId = row.ownerId
                )
            },
            // A full page means there is probably more — the same walk-until-short heuristic as
            // history, and for the same reason: precision costs a row the client cannot use.
            nextCursor = if (dialogs.size == pageSize) dialogs.last().id.toString() else null
        )
    }

    /**
     * A list cursor is a dialog the caller can see, so one that is not — unknown, malformed, or
     * somebody else's — is the client's mistake, a 400, not a 404: nothing was being *read* at that
     * id, a pagination parameter was wrong.
     */
    private fun resolveCursor(callerId: String, rawCursor: String): Dialog {
        val dialogId = rawCursor.toUuidOrBadRequest("cursor")
        val dialog = dialogRepository.findById(dialogId).orElse(null)
        if (dialog == null || callerId !in dialog.participantIds) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "Cursor dialog $rawCursor is not in your list")
        }
        return dialog
    }

    /**
     * The seen-by snapshot: where every member's cursor stands, so a client opening a group can
     * draw read ticks without replaying `message.read` frames it never saw. 404 for outsiders, like
     * every other dialog read.
     */
    @Transactional(readOnly = true)
    fun readState(callerId: String, rawDialogId: String): ReadStateResponse {
        val dialog = requireParticipant(callerId, rawDialogId)
        return ReadStateResponse(
            readStateRepository.findByDialog(dialog.id).map {
                ReadStateEntry(
                    userId = it.userId,
                    lastReadMessageId = it.lastReadId.toString(),
                    lastReadAt = it.lastReadAt
                )
            }
        )
    }

    /** One dialog, in the same shape as a list row. 404 unless the caller is in it. */
    @Transactional(readOnly = true)
    fun metadata(callerId: String, rawDialogId: String): DialogSummaryResponse {
        val dialog = requireParticipant(callerId, rawDialogId)
        return DialogSummaryResponse(
            dialogId = dialog.id.toString(),
            type = dialog.type.name.lowercase(),
            participantIds = dialog.participantIds.toList(),
            lastMessageAt = dialog.lastMessageAt,
            unreadCount = messageQueryRepository.countUnreadByDialog(callerId, listOf(dialog.id))[dialog.id] ?: 0L,
            createdAt = dialog.createdAt,
            title = dialog.title,
            ownerId = dialog.ownerId
        )
    }

    @Transactional(readOnly = true)
    fun requireParticipant(callerId: String, rawDialogId: String): Dialog =
        requireParticipant(callerId, rawDialogId.toUuidOrBadRequest("dialogId"))

    @Transactional(readOnly = true)
    fun requireParticipant(callerId: String, dialogId: UUID): Dialog {
        val dialog = dialogRepository.findById(dialogId).orElseThrow { dialogNotFound(dialogId) }
        if (callerId !in dialog.participantIds) throw dialogNotFound(dialogId)
        return dialog
    }

    private fun dialogNotFound(dialogId: UUID) =
        RelayException(HttpStatus.NOT_FOUND.value(), "Dialog $dialogId not found")

    companion object {
        /**
         * First-page sentinels above any real row — the `MessageHistoryService` pattern. The
         * timestamp doubles for both cursor components, since it exceeds any `last_message_at`
         * and any `created_at` alike.
         */
        private val AFTER_ANY_DIALOG: Instant = Instant.parse("9999-12-31T23:59:59Z")
        private val HIGHEST_UUID: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
    }
}

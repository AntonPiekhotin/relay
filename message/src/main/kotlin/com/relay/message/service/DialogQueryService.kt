package com.relay.message.service

import com.relay.common.exception.RelayException
import com.relay.message.model.Dialog
import com.relay.message.model.dto.DialogListResponse
import com.relay.message.model.dto.DialogSummaryResponse
import com.relay.message.repository.DialogQueryRepository
import com.relay.message.repository.DialogRepository
import com.relay.message.repository.MessageQueryRepository
import com.relay.message.util.toUuidOrBadRequest
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
    private val messageQueryRepository: MessageQueryRepository
) {

    /**
     * Every dialog the caller is in, most recently active first, each with the caller's own unread
     * count.
     *
     * This is what closes the discovery gap: before it, a conversation existed only for whoever
     * opened it. If Alice opened a dialog with Bob and sent while Bob was offline, Bob got a push and
     * then had nothing to fetch — the dialog id lived only on Alice's device, so the conversation was
     * invisible until Bob happened to be connected for a live `message.new`.
     *
     * Three queries regardless of how many dialogs come back: the dialogs, their membership, and one
     * grouped unread count. Not one plus three per row.
     */
    @Transactional(readOnly = true)
    fun list(callerId: String): DialogListResponse {
        val dialogs = dialogQueryRepository.findAllForUser(callerId)
        if (dialogs.isEmpty()) return DialogListResponse(emptyList())

        val ids = dialogs.map { it.id }
        val participants = dialogQueryRepository.findParticipantsByDialog(ids)
        val unread = messageQueryRepository.countUnreadByDialog(callerId, ids)

        return DialogListResponse(
            dialogs.map { row ->
                DialogSummaryResponse(
                    dialogId = row.id.toString(),
                    type = row.type.lowercase(),
                    participantIds = participants[row.id].orEmpty(),
                    lastMessageAt = row.lastMessageAt,
                    unreadCount = unread[row.id] ?: 0L,
                    createdAt = row.createdAt
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
            createdAt = dialog.createdAt
        )
    }

    /**
     * The authorization check every dialog read shares.
     *
     * A caller who is not a participant gets **404, not 403** — the opposite of the send path, which
     * answers `NOT_A_PARTICIPANT`. The difference is what the two leak. Sending needs a dialog id the
     * client already believes it has, so telling it "that one is not yours" is useful. Reading is
     * enumerable: a 403 here would confirm that a guessed dialog id exists, turning these endpoints
     * into an oracle for which conversations are real. To a caller with no business in a dialog, it
     * does not exist.
     */
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
}

package com.relay.message.service

import com.relay.common.exception.RelayException
import com.relay.message.model.DIRECT_KEY_SEPARATOR
import com.relay.message.model.Dialog
import com.relay.message.model.DialogType
import com.relay.message.model.dto.CreateDialogRequest
import com.relay.message.model.dto.DialogResponse
import com.relay.message.model.dto.OpenDialogResult
import com.relay.message.repository.DialogRepository
import com.relay.message.util.mapper.toResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class DialogService(
    private val dialogRepository: DialogRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * "Open the chat with this person" — the only way a client obtains a dialog id, and idempotent
     * by construction: the pair decides the row, so a retry, a second device, or both people
     * tapping at the same moment all converge on one dialog.
     *
     * Deliberately **not** `@Transactional`. Recovering from the unique violation means reading the
     * row the other side just committed, and a transaction that has flushed a failed insert cannot:
     * its persistence context is invalid and it is already marked rollback-only. Each repository
     * call below therefore runs in its own transaction, which Spring Data opens per method, so the
     * re-read happens on a clean one. Nothing here needs to be atomic anyway — the insert is a
     * single statement and the constraint is what enforces the invariant.
     */
    fun openDirect(callerId: String, peerId: String): OpenDialogResult {
        validateIds(callerId, peerId)

        val directKey = Dialog.directKeyOf(callerId, peerId)
        dialogRepository.findByDirectKey(directKey)?.let {
            return OpenDialogResult(it.toResponse(), created = false)
        }

        return try {
            createDialog(directKey, callerId, peerId)
        } catch (ex: DataIntegrityViolationException) {
            existingDialog(directKey, callerId, peerId, ex)
        }
    }

    private fun validateIds(callerId: String, peerId: String) {
        if (callerId == peerId) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "You cannot open a dialog with yourself") //todo: implement saved messages
        }
        if (DIRECT_KEY_SEPARATOR in peerId || DIRECT_KEY_SEPARATOR in callerId) {
            throw RelayException(
                HttpStatus.BAD_REQUEST.value(),
                "A user id cannot contain '$DIRECT_KEY_SEPARATOR'"
            )
        }
    }

    private fun createDialog(directKey: String, callerId: String, peerId: String): OpenDialogResult {
        val saved = dialogRepository.saveAndFlush(
            Dialog(
                type = DialogType.DIRECT,
                directKey = directKey,
                participantIds = mutableSetOf(callerId, peerId)
            )
        )
        logger.debug("Opened direct dialog {} between {} and {}", saved.id, callerId, peerId)
        return OpenDialogResult(saved.toResponse(), created = true)
    }

    /**
     * Fallback to returning the existing dialog, which is the one that won the race.
     * Applies only when two users simultaneously open the same dialog.
     */
    private fun existingDialog(
        directKey: String, callerId: String, peerId: String, ex: DataIntegrityViolationException
    ): OpenDialogResult {
        val winner = dialogRepository.findByDirectKey(directKey)
            ?: throw RelayException(
                HttpStatus.CONFLICT.value(),
                "Could not open a dialog between $callerId and $peerId",
                ex
            )
        logger.debug("Lost the race to open a dialog between {} and {}, returning {}", callerId, peerId, winner.id)
        return OpenDialogResult(winner.toResponse(), created = false)
    }

    /**
     * The `/internal` path. Both participants use `openDirect` instead of inserting directly, so the internal
     * and client-facing paths cannot create two dialogs for the same pair.
     * This is the same reason both send paths use `MessageService.send`.
     */
    fun create(request: CreateDialogRequest): DialogResponse {
        val participantIds = request.participantIds
        if (participantIds.size > 2) {
            return createGroup(participantIds)
        }
        val (first, second) = participantIds.toList()
        return openDirect(first, second).dialog
    }

    /**
     * A group carries no key, so nothing deduplicates it: the same three people asking twice get
     * two groups, which is what a group is — a thing you create, not a pair you look up.
     */
    private fun createGroup(participantIds: Set<String>): DialogResponse =
        dialogRepository.save(
            Dialog(type = DialogType.GROUP, directKey = null, participantIds = participantIds.toMutableSet())
        ).toResponse()
}

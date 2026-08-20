package com.relay.message.service

import com.relay.common.event.GroupChangeTypes
import com.relay.common.exception.RelayException
import com.relay.message.config.MessageProperties
import com.relay.message.model.Dialog
import com.relay.message.model.DialogType
import com.relay.message.model.Message
import com.relay.message.model.MessageKind
import com.relay.message.model.dto.CreateGroupDialogRequest
import com.relay.message.model.dto.event.GroupDialogChanged
import com.relay.message.repository.DialogRepository
import com.relay.message.repository.MessageRepository
import com.relay.message.repository.ReadStateRepository
import com.relay.message.util.toUuidOrBadRequest
import jakarta.persistence.EntityManager
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/** What a group mutation hands back: the dialog id to summarize, and whether this call made it. */
data class GroupMutationResult(val dialogId: UUID, val created: Boolean = false)

/**
 * Group dialogs: create, rename, membership, delete. The write side only — reads stay on
 * [DialogQueryService], and sending in a group is [MessageService] unchanged, because the send path
 * already fans out to `participantIds` however many there are.
 *
 * Two concurrency regimes, deliberately different:
 *
 * **Create** follows [DialogService.openDirect]'s reasoning: no outer transaction, because
 * recovering from a duplicate-id collision means reading the row another transaction committed, and
 * a transaction that has flushed a failed insert is rollback-only. The id is client-supplied and is
 * the idempotency key (`docs/DATA.md` §6.1's `calls.id` trick — a group has no natural uniqueness,
 * the same people may want two). The insert goes through `EntityManager.persist`, **not**
 * `save()`: the entity arrives with its id set, so `save()` would `merge` — and a merge that finds
 * the row already committed silently updates it instead of colliding, which would let a replayed
 * or hostile create overwrite a stored group. `persist` always INSERTs, so a duplicate is a
 * primary-key violation the catch below answers with the stored row.
 *
 * **Every other mutation** opens with `SELECT ... FOR UPDATE` on the dialog row
 * ([DialogRepository.findByIdForUpdate]) inside one transaction. The lock is what makes the member
 * cap and every transition race-free — two adds at 49 of 50, an add against a leave, a leave
 * against a delete — the same pattern invariant 11 mandates for group calls.
 *
 * Every change is persisted as a system message in the same transaction, so history carries it,
 * and announced after commit as a `GroupChanged` on `messages.delivery`, keyed by dialogId, so the
 * gateway invalidates its membership cache *in order* with the frames that membership produced.
 */
@Service
class GroupDialogService(
    private val dialogRepository: DialogRepository,
    private val messageRepository: MessageRepository,
    private val readStateRepository: ReadStateRepository,
    private val properties: MessageProperties,
    private val events: ApplicationEventPublisher,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    fun create(callerId: String, request: CreateGroupDialogRequest): GroupMutationResult {
        val dialogId = request.dialogId.toUuidOrBadRequest("dialogId")
        val title = request.title.trim()
        if (title.isEmpty()) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "title must not be blank")
        }
        val members = request.memberIds + callerId
        if (members.size < 2) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "a group needs at least one other member")
        }
        if (members.size > properties.groupMemberCap) {
            throw RelayException(
                HttpStatus.BAD_REQUEST.value(),
                "a group holds at most ${properties.groupMemberCap} members"
            )
        }

        // The replay fast path. Checked again after a collision, because two concurrent creates
        // with the same id both pass this read and one of them loses the insert.
        dialogRepository.findById(dialogId).orElse(null)?.let { return replayOf(it, callerId) }

        return try {
            transaction.execute {
                val dialog = Dialog(
                    id = dialogId,
                    type = DialogType.GROUP,
                    directKey = null,
                    title = title,
                    ownerId = callerId,
                    participantIds = members.toMutableSet()
                )
                entityManager.persist(dialog)
                entityManager.flush()

                val created = systemMessage(dialog, callerId, MessageKind.GROUP_CREATED)
                dialogRepository.touchLastMessageAt(dialog.id, created.sentAt)
                // Founding members start read: the only row past nobody's cursor is the one their
                // client is about to render as "group created", not a phantom unread badge.
                members.forEach { readStateRepository.advance(dialog.id, it, created.sentAt, created.id) }

                announce(dialog, GroupChangeTypes.GROUP_CREATED, callerId, message = created)
                logger.debug("Created group {} with {} members", dialog.id, members.size)
                GroupMutationResult(dialog.id, created = true)
            }!!
        } catch (ex: DataIntegrityViolationException) {
            val stored = dialogRepository.findById(dialogId).orElse(null)
                ?: throw RelayException(HttpStatus.CONFLICT.value(), "Could not create group $dialogId", ex)
            replayOf(stored, callerId)
        }
    }

    /**
     * A create that found its id already taken is either the same request again — same caller, a
     * group they own — or somebody else's id, and nothing legitimate produces the latter: UUID
     * collisions do not happen by accident, so it is answered 409 rather than leaked.
     */
    private fun replayOf(stored: Dialog, callerId: String): GroupMutationResult {
        if (stored.type != DialogType.GROUP || stored.ownerId != callerId) {
            throw RelayException(HttpStatus.CONFLICT.value(), "Dialog id ${stored.id} is already in use")
        }
        return GroupMutationResult(stored.id, created = false)
    }

    @Transactional
    fun rename(callerId: String, rawDialogId: String, rawTitle: String): GroupMutationResult {
        val title = rawTitle.trim()
        if (title.isEmpty()) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "title must not be blank")
        }
        val dialog = ownedGroupForUpdate(callerId, rawDialogId)
        if (dialog.title == title) return GroupMutationResult(dialog.id) // a repeat is not an error

        dialog.title = title
        val message = systemMessage(dialog, callerId, MessageKind.GROUP_RENAMED, text = title)
        dialogRepository.touchLastMessageAt(dialog.id, message.sentAt)
        announce(dialog, GroupChangeTypes.GROUP_RENAMED, callerId, message = message)
        return GroupMutationResult(dialog.id)
    }

    @Transactional
    fun addMembers(callerId: String, rawDialogId: String, userIds: Set<String>): GroupMutationResult {
        val dialog = ownedGroupForUpdate(callerId, rawDialogId)
        // Already-members are silent no-ops: "make sure these people are in" is idempotent intent.
        val newcomers = userIds - dialog.participantIds
        if (newcomers.isEmpty()) return GroupMutationResult(dialog.id)

        if (dialog.participantIds.size + newcomers.size > properties.groupMemberCap) {
            throw RelayException(
                HttpStatus.CONFLICT.value(),
                "a group holds at most ${properties.groupMemberCap} members"
            )
        }

        var last: Message? = null
        newcomers.forEach { userId ->
            dialog.participantIds += userId
            val message = systemMessage(dialog, callerId, MessageKind.MEMBER_ADDED, targetUserId = userId)
            // The join watermark: full history stays readable, but unread starts here — the seed
            // is the same monotonic upsert reads use, so a re-added member's cursor never moves back.
            readStateRepository.advance(dialog.id, userId, message.sentAt, message.id)
            announce(dialog, GroupChangeTypes.MEMBER_ADDED, callerId, targetUserId = userId, message = message)
            last = message
        }
        dialogRepository.touchLastMessageAt(dialog.id, last!!.sentAt)
        return GroupMutationResult(dialog.id)
    }

    @Transactional
    fun removeMember(callerId: String, rawDialogId: String, userId: String): GroupMutationResult {
        if (userId == callerId) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "the owner leaves by deleting the group")
        }
        val dialog = ownedGroupForUpdate(callerId, rawDialogId)
        if (userId !in dialog.participantIds) {
            throw RelayException(HttpStatus.NOT_FOUND.value(), "User $userId is not a member of ${dialog.id}")
        }

        dialog.participantIds -= userId
        readStateRepository.delete(dialog.id, userId)
        val message = systemMessage(dialog, callerId, MessageKind.MEMBER_REMOVED, targetUserId = userId)
        dialogRepository.touchLastMessageAt(dialog.id, message.sentAt)
        // The removed member is in the recipients — they need the frame that tells them they are out.
        announce(
            dialog, GroupChangeTypes.MEMBER_REMOVED, callerId,
            targetUserId = userId, message = message, extraRecipient = userId
        )
        return GroupMutationResult(dialog.id)
    }

    @Transactional
    fun leave(callerId: String, rawDialogId: String) {
        val dialog = groupForUpdate(callerId, rawDialogId)
        if (dialog.ownerId == callerId) {
            // 422: the request is well-formed and authorized, the group's state is what refuses it.
            throw RelayException(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "the owner cannot leave; delete the group instead"
            )
        }

        dialog.participantIds -= callerId
        readStateRepository.delete(dialog.id, callerId)
        val message = systemMessage(dialog, callerId, MessageKind.MEMBER_LEFT, targetUserId = callerId)
        dialogRepository.touchLastMessageAt(dialog.id, message.sentAt)
        announce(
            dialog, GroupChangeTypes.MEMBER_LEFT, callerId,
            targetUserId = callerId, message = message, extraRecipient = callerId
        )
    }

    @Transactional
    fun delete(callerId: String, rawDialogId: String) {
        val dialog = ownedGroupForUpdate(callerId, rawDialogId)
        val recipients = dialog.participantIds.toSet()

        // Read state carries an FK to dialogs, so it goes first; messages carry none, so they are
        // deleted explicitly or they orphan. Participants go with the entity's element collection.
        readStateRepository.deleteAll(dialog.id)
        messageRepository.deleteAllByDialogId(dialog.id)
        dialogRepository.delete(dialog)

        events.publishEvent(
            GroupDialogChanged(
                dialogId = dialog.id.toString(),
                change = GroupChangeTypes.GROUP_DELETED,
                actorId = callerId,
                targetUserId = null,
                title = dialog.title,
                message = null,
                recipientIds = recipients
            )
        )
        logger.debug("Deleted group {} with {} members", dialog.id, recipients.size)
    }

    /**
     * The locked entry point of a member-level mutation: the dialog row, `FOR UPDATE`.
     *
     * The answers mirror the read side's reasoning ([DialogQueryService.requireParticipant]):
     * unknown dialog and not-a-participant are both **404** so these endpoints cannot confirm
     * guessed ids; a mutation on a direct dialog is **400**, because the caller demonstrably holds
     * the real id and the mistake is the operation, not the target.
     */
    private fun groupForUpdate(callerId: String, rawDialogId: String): Dialog {
        val dialogId = rawDialogId.toUuidOrBadRequest("dialogId")
        val dialog = dialogRepository.findByIdForUpdate(dialogId)
            ?: throw dialogNotFound(dialogId)
        if (callerId !in dialog.participantIds) throw dialogNotFound(dialogId)
        if (dialog.type != DialogType.GROUP) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "Dialog $dialogId is not a group")
        }
        return dialog
    }

    /** [groupForUpdate], plus the admin check: participant-but-not-owner already knows the group exists, so 403. */
    private fun ownedGroupForUpdate(callerId: String, rawDialogId: String): Dialog {
        val dialog = groupForUpdate(callerId, rawDialogId)
        if (dialog.ownerId != callerId) {
            throw RelayException(HttpStatus.FORBIDDEN.value(), "Only the group owner can do that")
        }
        return dialog
    }

    /**
     * A membership change as a row in `messages`, so it orders, pages, and counts like any message.
     * The idempotency key is server-minted — it satisfies `uk_messages_sender_client_id` and means
     * nothing to anyone, which is why history withholds it.
     */
    private fun systemMessage(
        dialog: Dialog,
        actorId: String,
        kind: MessageKind,
        targetUserId: String? = null,
        text: String = ""
    ): Message =
        messageRepository.saveAndFlush(
            Message(
                dialogId = dialog.id,
                senderId = actorId,
                text = text,
                clientMessageId = UUID.randomUUID().toString(),
                kind = kind,
                targetUserId = targetUserId
            )
        )

    private fun announce(
        dialog: Dialog,
        change: String,
        actorId: String,
        targetUserId: String? = null,
        message: Message? = null,
        extraRecipient: String? = null
    ) {
        events.publishEvent(
            GroupDialogChanged(
                dialogId = dialog.id.toString(),
                change = change,
                actorId = actorId,
                targetUserId = targetUserId,
                title = dialog.title,
                message = message,
                recipientIds = dialog.participantIds.toSet() + setOfNotNull(extraRecipient)
            )
        )
    }

    private fun dialogNotFound(dialogId: UUID) =
        RelayException(HttpStatus.NOT_FOUND.value(), "Dialog $dialogId not found")
}

package com.relay.message.service

import com.relay.common.event.MarkReadCommand
import com.relay.common.event.MessageDeliveryEvent
import com.relay.common.exception.RelayException
import com.relay.message.repository.MessageRepository
import com.relay.message.repository.ReadStateRepository
import com.relay.message.util.toUuidOrBadRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

/**
 * Moves a user's read cursor and reports the receipt.
 *
 * The cursor is a position, so this is idempotent and order-insensitive by construction — see
 * [ReadStateRepository.advance], which is where the monotonicity actually lives.
 */
@Service
class ReadStateService(
    private val readStateRepository: ReadStateRepository,
    private val messageRepository: MessageRepository,
    private val dialogQueryService: DialogQueryService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Applies [command] and returns the receipt to announce, or **null when the cursor did not
     * move** — because the client had already read that far, or because a command carrying an older
     * position arrived late. Returning null rather than a receipt is what stops a retried read from
     * firing a second read tick on the other participant's screen.
     *
     * Not `@Transactional`: the upsert is a single statement whose own `where` clause is the
     * concurrency control, and the two reads before it are independent lookups. A transaction would
     * add a boundary without adding an invariant.
     */
    fun markRead(command: MarkReadCommand): MessageDeliveryEvent.Read? {
        val dialog = dialogQueryService.requireParticipant(command.readerId, command.dialogId)
        val upToId = command.upToMessageId.toUuidOrBadRequest("upToMessageId")

        val upTo = messageRepository.findByIdAndDialogId(upToId, dialog.id)
            ?: throw RelayException(
                HttpStatus.BAD_REQUEST.value(),
                "Message ${command.upToMessageId} is not in dialog ${dialog.id}"
            )

        // advance cursor or return null if the cursor is already at or past the message
        readStateRepository.advance(
            dialogId = dialog.id,
            userId = command.readerId,
            lastReadAt = upTo.sentAt,
            lastReadId = upTo.id
        ).let { if (!it) return null }

        return MessageDeliveryEvent.Read(
            dialogId = dialog.id.toString(),
            readerId = command.readerId,
            readerSessionId = command.readerSessionId,
            upToMessageId = upTo.id.toString(),
            lastReadAt = upTo.sentAt,
            recipientIds = dialog.participantIds.toList()
        )
    }
}

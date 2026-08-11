package com.relay.message.service

import com.relay.common.exception.RelayException
import com.relay.message.config.MessageProperties
import com.relay.message.model.Message
import com.relay.message.model.dto.HistoryMessageResponse
import com.relay.message.model.dto.MessageHistoryResponse
import com.relay.message.repository.MessageQueryRepository
import com.relay.message.repository.MessageRepository
import com.relay.message.repository.MessageRow
import com.relay.message.util.toUuidOrBadRequest
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Message history, in both directions a client needs it.
 *
 * These are not two features. `before` is the backwards scroll a user drives; `after` is catch-up
 * after the socket dropped, and it is the mechanism the entire delivery design rests on — the gateway
 * buffers nothing for a client that is not connected, which is only acceptable because the database
 * is authoritative and this query recovers the gap (`docs/ARCHITECTURE.md` Principle 1,
 * `docs/PROTOCOL.md` §7). One cursor, two comparison directions.
 */
@Service
class MessageHistoryService(
    private val messageRepository: MessageRepository,
    private val messageQueryRepository: MessageQueryRepository,
    private val dialogQueryService: DialogQueryService,
    private val properties: MessageProperties
) {

    /**
     * One page of [rawDialogId]'s messages.
     *
     * At most one of [before] / [after] may be given: they name opposite directions from a position,
     * and a request carrying both is asking for two different pages. Answering it by silently
     * preferring one would hand back a page the client did not ask for and cannot detect.
     *
     * With neither, the newest page — which is what a chat opens on.
     */
    @Transactional(readOnly = true)
    fun history(
        callerId: String,
        rawDialogId: String,
        before: String?,
        after: String?,
        limit: Int?
    ): MessageHistoryResponse {
        if (before != null && after != null) {
            throw RelayException(
                HttpStatus.BAD_REQUEST.value(),
                "before and after are opposite directions; pass at most one"
            )
        }
        val dialogId = rawDialogId.toUuidOrBadRequest("dialogId")
        dialogQueryService.requireParticipant(callerId, dialogId)

        val pageSize = (limit ?: properties.historyPageSize)
            .coerceIn(1, properties.maxHistoryPageSize)

        val rows = if (after != null) {
            val cursor = resolveCursor(dialogId, after, "after")
            messageQueryRepository.findPageAfter(dialogId, cursor.sentAt, cursor.id, pageSize)
        } else {
            val cursor = before?.let { resolveCursor(dialogId, it, "before") }
            messageQueryRepository.findPageBefore(
                dialogId = dialogId,
                beforeSentAt = cursor?.sentAt ?: NEWER_THAN_ANY_MESSAGE,
                beforeId = cursor?.id ?: HIGHEST_UUID,
                limit = pageSize
            )
        }

        return MessageHistoryResponse(
            messages = rows.map { it.toResponse(callerId) },
            nextCursor = nextCursor(rows, pageSize)
        )
    }

    /**
     * A cursor is a message id the client already holds, so one that does not resolve inside this
     * dialog is a `400` rather than an empty page. Silently treating it as "no cursor" would hand a
     * client scrolling backwards the newest page instead, which looks like the conversation jumping
     * to the bottom rather than like the bug it is.
     */
    private fun resolveCursor(dialogId: UUID, rawId: String, param: String): Message =
        messageRepository.findByIdAndDialogId(rawId.toUuidOrBadRequest(param), dialogId)
            ?: throw RelayException(
                HttpStatus.BAD_REQUEST.value(),
                "Cursor message $rawId is not in dialog $dialogId"
            )

    /**
     * The last element of the page, in whichever direction it was read — so a client pages backwards
     * and forwards with the same line of code.
     *
     * Null on a short page. A full page means there is *probably* more and one extra request finds
     * out; the alternative is fetching `pageSize + 1` rows to know for certain, which buys precision
     * a scrolling list cannot use. Catch-up is the case that matters and it terminates either way:
     * the client keeps paging until a page comes back short.
     */
    private fun nextCursor(rows: List<MessageRow>, pageSize: Int): String? =
        if (rows.size == pageSize) rows.last().id.toString() else null
    /** [clientMessageId] is the sender's own key; nobody else has any use for it. See the DTO. */
    private fun MessageRow.toResponse(callerId: String) = HistoryMessageResponse(
        messageId = id.toString(),
        dialogId = dialogId.toString(),
        senderId = senderId,
        text = text,
        createdAt = sentAt,
        clientMsgId = clientMessageId.takeIf { senderId == callerId }
    )

    companion object {
        /**
         * The first page passes a sentinel cursor rather than nulls, matching `CallRepository`:
         * `:param is null` reads better but leaves Postgres unable to infer the parameter's type,
         * and casting it back into shape costs more clarity than the sentinel does. Both cursor
         * comparisons are strict, so a position above every real row matches everything.
         */
        private val NEWER_THAN_ANY_MESSAGE: Instant = Instant.parse("9999-12-31T23:59:59Z")
        private val HIGHEST_UUID: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
    }
}

package com.relay.common.event

/**
 * A client moving its read cursor forward, published by websocket-gateway to `messages.read` and
 * consumed by message-service. Keyed by [dialogId].
 *
 * [upToMessageId] is a position, not a message being acted on: everything in the dialog up to and
 * including it counts as read. A cursor is what makes this idempotent and order-insensitive — the
 * same command applied twice, or two commands arriving out of order, converge on the furthest
 * position rather than double-counting or moving backwards.
 *
 * [readerId] is set by the gateway from the authenticated session, never from the frame, for the
 * same reason [SendMessageCommand.senderId] is: a client must not be able to mark somebody else's
 * conversation read.
 *
 * [readerSessionId] identifies the exact connection that read, so the resulting receipt can skip
 * that device — it already knows — while still reaching the reader's *other* devices, which need
 * it to clear their unread badge.
 */
data class MarkReadCommand(
    val dialogId: String,
    val readerId: String,
    val readerSessionId: String,
    val upToMessageId: String
)

package com.relay.message.model.dto

import java.time.Instant

/**
 * One message as history returns it.
 *
 * `createdAt` rather than `sentAt`, which is what the column is called: the wire contract has said
 * `created_at` since the first `ack` frame shipped (`docs/PROTOCOL.md` §4.1), and a client merging a
 * history row with a `message.new` it already holds must not have to reconcile two names for the
 * same instant. The column keeps its name; the boundary translates.
 *
 * [clientMsgId] is **null on other people's messages**. It is the sender's own idempotency key, and
 * its only use to a client is merging a history row against a send still `PENDING` in its outbox —
 * which can only ever be its own. Returning somebody else's would leak a value that means nothing
 * to the reader and something to its owner.
 */
data class HistoryMessageResponse(
    val messageId: String,
    val dialogId: String,
    val senderId: String,
    val text: String,
    val createdAt: Instant,
    val clientMsgId: String?,

    /**
     * `user` for what people send; `group_created` / `member_added` / `member_removed` /
     * `member_left` / `group_renamed` for membership system messages, which live in the same
     * history so they page and order like any message. Lowercase like every other REST vocabulary.
     * Additive — a client that predates groups never sees anything but `user` in its dialogs.
     */
    val kind: String = "user",

    /** The member a membership system message is about; null for `user` rows. An id, not a name. */
    val targetUserId: String? = null
)

/**
 * A page of history. [nextCursor] is the message id to pass back as `before` (or `after`, whichever
 * direction this page was fetched in) for the following page, and is null on the last one.
 *
 * Note the direction difference: a `before` page is newest-first and a `after` page is oldest-first,
 * but in both cases the cursor for the next page is the **last element of this one**. A client walks
 * either direction with the same line of code.
 */
data class MessageHistoryResponse(
    val messages: List<HistoryMessageResponse>,
    val nextCursor: String?
)

package com.relay.message.model.dto.event

import com.relay.message.model.Message

/**
 * Domain event raised inside a group mutation's transaction by `GroupDialogService`; the output
 * adapter turns it into a Kafka `messages.delivery` `GroupChanged` only once the transaction
 * commits — same shape and same reasoning as [MessagePersisted].
 *
 * [message] is the persisted system message, null only for a group delete, which removes the
 * dialog's messages instead of adding one. [recipientIds] is the post-change membership plus the
 * removed or left member, who needs the frame that tells them they are out.
 */
data class GroupDialogChanged(
    val dialogId: String,
    val change: String,
    val actorId: String,
    val targetUserId: String?,
    val title: String?,
    val message: Message?,
    val recipientIds: Set<String>
)

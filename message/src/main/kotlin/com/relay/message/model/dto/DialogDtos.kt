package com.relay.message.model.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * The `/internal` shape: the caller names every participant, including itself. Kept off `common`
 * until something else needs it.
 */
data class CreateDialogRequest(

    @field:NotEmpty
    @field:Size(min = 2, message = "a dialog needs at least two participants")
    val participantIds: Set<String>
)

/**
 * The client-facing shape. Only the other person is named — the caller is the JWT subject, so a
 * client cannot open a conversation on somebody else's behalf.
 */
data class OpenDirectDialogRequest(

    @field:NotBlank
    @field:Size(max = 64, message = "peerId is at most 64 characters")
    val peerId: String
)

data class DialogResponse(
    val id: String,
    val type: String,
    val participantIds: Set<String>,
    val createdAt: Instant
)

/** [created] is false when the dialog already existed — a repeat open is not an error. */
data class OpenDialogResult(val dialog: DialogResponse, val created: Boolean)

/**
 * One row of the dialog list, and the payload of the single-dialog lookup.
 *
 * [participantIds] is what makes a direct dialog identifiable at all: `type` is `direct` and there
 * is no title, so the only way a client knows whose conversation this is, is by seeing who is in it
 * and subtracting itself. It then resolves the peer through `GET /api/v1/user/{id}` — message-service
 * holds no names (`docs/DATA.md` §1).
 *
 * [lastMessageAt] is null for a dialog opened but never used, and is what the list is ordered by.
 * [unreadCount] counts messages from other people past the caller's read cursor, so it is relative
 * to whoever asked — the same dialog has a different count for each participant.
 *
 * The key is `dialogId`, not `id`, matching the shape in `docs/PROTOCOL.md` §5.1. Note that
 * `POST /dialogs` answers with [DialogResponse], whose key is `id` — that endpoint shipped first and
 * its shape is a contract already (CLAUDE.md invariant 8), so the two are not unified.
 */
data class DialogSummaryResponse(
    val dialogId: String,
    val type: String,
    val participantIds: List<String>,
    val lastMessageAt: Instant?,
    val unreadCount: Long,
    val createdAt: Instant,

    /** Groups only — null for `direct`, whose name is its membership. */
    val title: String? = null,

    /**
     * The group's single admin, so a client knows whether to draw the management controls. Null for
     * `direct` and for legacy admin-less groups, where nobody gets them.
     */
    val ownerId: String? = null
)

/**
 * One page of the list. [nextCursor] is a dialog id to pass back as `cursor` for the following
 * page, null on the last one — the same walk-until-short contract as message history. Additive:
 * a client that predates pagination never sends `cursor` and reads the first (and, for it,
 * usually only) page.
 */
data class DialogListResponse(
    val dialogs: List<DialogSummaryResponse>,
    val nextCursor: String? = null
)

/**
 * A group create. [dialogId] is client-supplied and is the idempotency key, the `calls.id` trick
 * (`docs/DATA.md` §6.1): a group has no natural uniqueness — the same three people may want two
 * groups — so the client's UUID is what makes a retried create return the group it already made
 * instead of a twin.
 */
data class CreateGroupDialogRequest(

    @field:NotBlank
    val dialogId: String,

    @field:NotBlank
    @field:Size(max = 128, message = "title is at most 128 characters")
    val title: String,

    /** The caller is implicit and added regardless — a client cannot create a group it is not in. */
    @field:NotEmpty
    val memberIds: Set<@Size(max = 64, message = "a user id is at most 64 characters") String>
)

data class RenameGroupRequest(

    @field:NotBlank
    @field:Size(max = 128, message = "title is at most 128 characters")
    val title: String
)

data class AddMembersRequest(

    @field:NotEmpty
    val userIds: Set<@Size(max = 64, message = "a user id is at most 64 characters") String>
)

/**
 * The seen-by snapshot: every member's read cursor in one dialog. A member who has never read is
 * absent. Live updates arrive as `message.read` frames; this is the state a client starts from.
 */
data class ReadStateResponse(val entries: List<ReadStateEntry>)

data class ReadStateEntry(
    val userId: String,
    val lastReadMessageId: String,
    val lastReadAt: Instant
)

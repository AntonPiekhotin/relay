package com.relay.call.service.sfu

/**
 * The SFU's own view of a room, and the one server-side action taken against it.
 *
 * Used only outside transactions: reconciliation reads [participantIdentities] before opening the
 * per-call transaction, and [closeRoom] runs after a terminal transition commits — a network call
 * must never ride inside a transaction.
 */
interface RoomDirectory {

    /**
     * Who the SFU believes is connected to [room], or null when the room does not exist (which a
     * finished or never-started room legitimately does not). Throws on transport failure — the
     * caller decides whether a failed read is skippable.
     */
    fun participantIdentities(room: String): Set<String>?

    /**
     * Closes the room, disconnecting anyone still in it. Best-effort: the SFU's own empty-room
     * timeout cleans up if this fails, so callers log and move on.
     */
    fun closeRoom(room: String)
}

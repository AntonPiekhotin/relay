package com.relay.websocket.presence

import com.relay.websocket.util.PresenceProperties
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/**
 * When each user was last connected to this node. In memory, and deliberately nowhere else.
 *
 * Presence is the one traffic class that is never persisted (`docs/ARCHITECTURE.md` Principle 3), and
 * last-seen is presence. Writing it would mean a database write on every disconnect — on the service
 * that owns no data — to make a nice-to-have field survive a restart.
 *
 * **The consequence, and it is visible to users:** after a gateway restart a peer reads as offline
 * with no last-seen until they connect and disconnect again. The alternative is a row per user per
 * disconnect, which is a real cost for a field a client renders as "last seen recently" anyway.
 */
@Component
class LastSeenStore(private val properties: PresenceProperties) {

    private val lastSeenByUser = ConcurrentHashMap<String, Instant>()

    fun record(userId: String, at: Instant) {
        if (lastSeenByUser.size >= properties.maxLastSeenEntries && !lastSeenByUser.containsKey(userId)) {
            evictOldest()
        }
        lastSeenByUser[userId] = at
    }

    /** Null when this node has never seen the user go offline. */
    fun of(userId: String): Instant? = lastSeenByUser[userId]

    /**
     * Drops the oldest tenth once the cap is reached, rather than one entry per insert — a sort this
     * size is not something to do on every disconnect, and the oldest timestamps are both the least
     * useful and the least likely to be asked for.
     */
    private fun evictOldest() {
        val target = properties.maxLastSeenEntries * 9 / 10
        val excess = lastSeenByUser.size - target
        if (excess <= 0) return
        lastSeenByUser.entries
            .sortedBy { it.value }
            .take(excess)
            .forEach { lastSeenByUser.remove(it.key, it.value) }
    }
}

package com.relay.call.service

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/**
 * The disconnect sweep's memory of who was observed gone, and since when.
 *
 */
@Component
class DisconnectTracker {

    private data class Mark(val callId: UUID, val userId: String)

    private val firstMissedAt = ConcurrentHashMap<Mark, Instant>()

    fun observeGone(callId: UUID, userId: String, now: Instant): Instant =
        firstMissedAt.getOrPut(Mark(callId, userId)) { now }

    fun observePresent(callId: UUID, userId: String) {
        firstMissedAt.remove(Mark(callId, userId))
    }

    /** Drops the marks of calls no longer live, so settled calls do not leak entries. */
    fun retainCalls(liveCallIds: Set<UUID>) {
        firstMissedAt.keys.removeIf { it.callId !in liveCallIds }
    }
}

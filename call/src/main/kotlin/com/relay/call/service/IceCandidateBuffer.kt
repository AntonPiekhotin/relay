package com.relay.call.service

import com.relay.call.config.CallProperties
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** A candidate that arrived before the call it belongs to existed. */
data class BufferedCandidate(
    val fromUserId: String,
    val candidate: Map<String, Any?>,
    val receivedAt: Instant
)

/**
 * Holds ICE candidates for calls that do not exist yet.
 *
 * Trickle ICE starts the moment the caller creates its offer, so candidates genuinely arrive before
 * the invite that created the call — and discarding them breaks call setup rather than merely
 * slowing it (`docs/PROTOCOL.md` §4.4). They are held for `relay.call.ice-buffer-ttl` and then
 * dropped.
 *
 * **Bounded, in both directions.** The call id is client-supplied, so an unbounded map here is a
 * memory attack: spray candidates for random ids and the heap fills. Overflow is dropped and
 * logged, which is the same trade the outbound socket buffer makes.
 *
 * All mutation goes through `compute`, so it is atomic under the map's per-bin lock — the pattern
 * `InMemorySessionRegistry` uses for the same reason.
 */
@Component
class IceCandidateBuffer(private val properties: CallProperties) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val buffered = ConcurrentHashMap<UUID, MutableList<BufferedCandidate>>()

    /** False when the candidate was dropped because a bound was reached. */
    fun buffer(callId: UUID, fromUserId: String, candidate: Map<String, Any?>): Boolean {
        if (buffered.size >= MAX_PENDING_CALLS && !buffered.containsKey(callId)) {
            logger.warn(
                "ICE buffer holds {} unknown calls, dropping a candidate from {}",
                MAX_PENDING_CALLS, fromUserId
            )
            return false
        }
        var accepted = false
        buffered.compute(callId) { _, existing ->
            val candidates = existing ?: mutableListOf()
            if (candidates.size < MAX_CANDIDATES_PER_CALL) {
                candidates += BufferedCandidate(fromUserId, candidate, Instant.now())
                accepted = true
            }
            candidates
        }
        if (!accepted) {
            logger.warn("Call {} already has {} buffered candidates, dropping", callId, MAX_CANDIDATES_PER_CALL)
        }
        return accepted
    }

    /** Takes everything held for a call and forgets it. */
    fun drain(callId: UUID): List<BufferedCandidate> = buffered.remove(callId) ?: emptyList()

    fun pendingCallIds(): Set<UUID> = buffered.keys.toSet()

    /** Drops candidates past their TTL. Returns how many were dropped. */
    fun evictExpired(now: Instant = Instant.now()): Int {
        var evicted = 0
        for (callId in buffered.keys) {
            buffered.compute(callId) { _, candidates ->
                if (candidates == null) return@compute null
                val before = candidates.size
                candidates.removeIf { now.isAfter(it.receivedAt.plus(properties.iceBufferTtl)) }
                evicted += before - candidates.size
                candidates.ifEmpty { null }
            }
        }
        if (evicted > 0) {
            logger.debug("Dropped {} ICE candidate(s) whose call never appeared", evicted)
        }
        return evicted
    }

    companion object {
        /** Enough for a full trickle-ICE gathering round from one peer, and no more. */
        const val MAX_CANDIDATES_PER_CALL = 64

        /** Distinct unknown call ids held at once. */
        const val MAX_PENDING_CALLS = 1024
    }
}

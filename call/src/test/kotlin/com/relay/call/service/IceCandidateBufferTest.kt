package com.relay.call.service

import com.relay.call.config.CallProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The buffer's bounds are the interesting part. The call id is client-supplied, so an unbounded map
 * here would be a memory attack rather than a convenience.
 */
class IceCandidateBufferTest {

    private fun buffer(ttl: Duration = Duration.ofSeconds(5)) =
        IceCandidateBuffer(CallProperties(iceBufferTtl = ttl))

    private fun candidate(index: Int) = mapOf<String, Any?>("candidate" to "candidate:$index")

    @Test
    fun `holds candidates for a call that does not exist yet and hands them over in order`() {
        val buffer = buffer()
        val callId = UUID.randomUUID()

        assertTrue(buffer.buffer(callId, "alice", candidate(1)))
        assertTrue(buffer.buffer(callId, "alice", candidate(2)))

        val drained = buffer.drain(callId)
        assertEquals(listOf(candidate(1), candidate(2)), drained.map { it.candidate })
        assertTrue(buffer.drain(callId).isEmpty(), "draining takes the candidates, it does not copy them")
    }

    @Test
    fun `drops candidates past their ttl`() {
        val buffer = buffer(ttl = Duration.ofSeconds(5))
        val callId = UUID.randomUUID()
        buffer.buffer(callId, "alice", candidate(1))

        assertEquals(0, buffer.evictExpired(Instant.now()), "nothing is expired yet")
        assertEquals(1, buffer.evictExpired(Instant.now().plusSeconds(6)))
        assertTrue(buffer.pendingCallIds().isEmpty(), "an emptied call leaves no entry behind")
    }

    @Test
    fun `refuses more candidates than one gathering round could produce`() {
        val buffer = buffer()
        val callId = UUID.randomUUID()

        repeat(IceCandidateBuffer.MAX_CANDIDATES_PER_CALL) {
            assertTrue(buffer.buffer(callId, "alice", candidate(it)))
        }

        assertFalse(
            buffer.buffer(callId, "alice", candidate(9999)),
            "one call must not be able to grow without bound"
        )
        assertEquals(IceCandidateBuffer.MAX_CANDIDATES_PER_CALL, buffer.drain(callId).size)
    }

    @Test
    fun `refuses candidates for new calls once too many unknown ids are held`() {
        val buffer = buffer()
        repeat(IceCandidateBuffer.MAX_PENDING_CALLS) {
            assertTrue(buffer.buffer(UUID.randomUUID(), "alice", candidate(it)))
        }

        val fresh = UUID.randomUUID()
        assertFalse(
            buffer.buffer(fresh, "mallory", candidate(1)),
            "spraying candidates at random call ids must not fill the heap"
        )
        assertTrue(buffer.drain(fresh).isEmpty())
    }

    @Test
    fun `still accepts candidates for a call already held when the id bound is reached`() {
        val buffer = buffer()
        val known = UUID.randomUUID()
        buffer.buffer(known, "alice", candidate(0))
        repeat(IceCandidateBuffer.MAX_PENDING_CALLS - 1) {
            buffer.buffer(UUID.randomUUID(), "alice", candidate(it))
        }

        assertTrue(
            buffer.buffer(known, "alice", candidate(1)),
            "a call already being gathered for must not be starved by the bound"
        )
    }
}

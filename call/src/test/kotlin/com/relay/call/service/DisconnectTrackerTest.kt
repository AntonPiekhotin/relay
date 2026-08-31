package com.relay.call.service

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DisconnectTrackerTest {

    private val tracker = DisconnectTracker()
    private val callId: UUID = UUID.randomUUID()
    private val t0: Instant = Instant.parse("2026-08-31T10:00:00Z")
    private val t1: Instant = t0.plusSeconds(30)

    @Test
    fun `the first observation is the one that counts`() {
        tracker.observeGone(callId, "alice", t0)

        assertEquals(t0, tracker.observeGone(callId, "alice", t1), "a later pass must not restart the clock")
    }

    @Test
    fun `a participant seen again is forgotten immediately`() {
        tracker.observeGone(callId, "alice", t0)
        tracker.observePresent(callId, "alice")

        assertEquals(t1, tracker.observeGone(callId, "alice", t1), "the blip was outwaited; the clock restarts")
    }

    @Test
    fun `marks of settled calls are dropped`() {
        val liveCall = UUID.randomUUID()
        tracker.observeGone(callId, "alice", t0)
        tracker.observeGone(liveCall, "bob", t0)

        tracker.retainCalls(setOf(liveCall))

        assertEquals(t1, tracker.observeGone(callId, "alice", t1), "the settled call's mark is gone")
        assertEquals(t0, tracker.observeGone(liveCall, "bob", t1), "the live call's mark survived")
    }
}

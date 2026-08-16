package com.relay.websocket.session

import com.relay.common.model.UserPrincipal
import com.relay.websocket.protocol.OutboundFrame
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One live WebSocket connection. A user may hold several at once (phone plus web), so sessions
 * are identified separately from users.
 *
 * Outbound frames go through a bounded queue drained by a single writer thread: the buffer
 * absorbs bursts, overflow is reported to the caller instead of growing without limit, and the
 * one-writer rule is what makes it safe to hand a [org.springframework.web.socket.WebSocketSession]
 * — which does not tolerate concurrent sends — to fan-out running on many Kafka listener threads.
 */
class RelaySession(
    val sessionId: String,
    val principal: UserPrincipal,
    private val outboundBufferSize: Int
) {

    /** What the writer thread pulls off the queue: either a frame to send, or a reason to stop. */
    sealed interface Outbound {
        data class Frame(val frame: OutboundFrame) : Outbound

        /** Graceful end: flush what is already queued, then close normally. */
        data object Completed : Outbound

        /** The client fell behind: flush what is queued, then close with an overload status. */
        data object Overloaded : Outbound
    }

    // One slot beyond the advertised buffer is reserved for the terminal marker, so a session can
    // always be torn down even when its buffer is completely full.
    private val outbound = ArrayBlockingQueue<Outbound>(outboundBufferSize + 1)
    private val buffered = AtomicInteger(0)
    private val ending = AtomicBoolean(false)

    val userId: String get() = principal.userId

    /**
     * True once this session is shutting down, whether cleanly or as a slow consumer. Nothing will
     * be delivered to it again, which is what lets a subscriber list prune itself instead of relying
     * on a close callback that may have raced.
     */
    val isEnding: Boolean get() = ending.get()

    /**
     * Returns false when the buffer is full or the session is already ending, i.e. the client is
     * not keeping up and its socket should be closed.
     *
     * Fan-out runs on several Kafka listener threads, so two frames can race for one session. The
     * slot is claimed with a CAS before the frame is queued, which keeps the reserved terminal slot
     * out of reach of ordinary sends no matter how the racers interleave.
     */
    fun send(frame: OutboundFrame): Boolean {
        if (ending.get()) return false
        while (true) {
            val claimed = buffered.get()
            if (claimed >= outboundBufferSize) return false
            if (buffered.compareAndSet(claimed, claimed + 1)) break
        }
        if (!outbound.offer(Outbound.Frame(frame))) {
            buffered.decrementAndGet()
            return false
        }
        return true
    }

    /**
     * Blocks the writer thread until there is something to do. Never blocks forever on a live
     * session: every termination path enqueues a marker, which is what wakes a parked writer.
     */
    fun awaitOutbound(): Outbound = outbound.take().also(::released)

    /** Bounded variant for callers that must not park indefinitely. Null when nothing arrived. */
    fun awaitOutbound(timeout: Duration): Outbound? =
        outbound.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)?.also(::released)

    private fun released(taken: Outbound) {
        if (taken is Outbound.Frame) buffered.decrementAndGet()
    }

    /** Ends the outbound stream so the writer drains the buffer and closes the socket. */
    fun complete() {
        if (ending.compareAndSet(false, true)) {
            outbound.put(Outbound.Completed)
        }
    }

    /**
     * Ends the outbound stream with an overload status. Already-buffered frames are still handed
     * to the writer first, matching what the reactive sink did on `tryEmitError`.
     */
    fun terminateOverloaded() {
        if (ending.compareAndSet(false, true)) {
            outbound.put(Outbound.Overloaded)
        }
    }
}
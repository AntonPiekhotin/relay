package com.relay.websocket.session

import com.relay.common.model.UserPrincipal
import com.relay.websocket.protocol.OutboundFrame
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.util.concurrent.Queues

/** Raised into the outbound stream when a client stops keeping up, which closes its socket. */
class OutboundOverflowException(sessionId: String) :
    RuntimeException("Session $sessionId fell behind its outbound buffer")

/** How long to spin when another thread is mid-emission on the same sink. */
private const val SERIALIZATION_RETRIES = 64

/**
 * One live WebSocket connection. A user may hold several at once (phone plus web), so sessions
 * are identified separately from users.
 *
 * Outbound frames go through a bounded sink: the buffer absorbs bursts, and overflow is reported
 * to the caller instead of growing without limit.
 */
class RelaySession(
    val sessionId: String,
    val principal: UserPrincipal,
    outboundBufferSize: Int
) {

    private val sink: Sinks.Many<OutboundFrame> =
        Sinks.many().unicast().onBackpressureBuffer(Queues.get<OutboundFrame>(outboundBufferSize).get())

    val userId: String get() = principal.userId

    val frames: Flux<OutboundFrame> = sink.asFlux()

    /**
     * Returns false when the buffer is full or the sink is already terminated, i.e. the client is
     * not keeping up and its socket should be closed.
     *
     * Fan-out runs on several Kafka listener threads, so two frames can race for one session.
     * Sinks report that as FAIL_NON_SERIALIZED rather than corrupting state, and the documented
     * response is to retry — treating it as a failure would silently drop deliverable frames.
     */
    fun send(frame: OutboundFrame): Boolean {
        repeat(SERIALIZATION_RETRIES) {
            when (sink.tryEmitNext(frame)) {
                Sinks.EmitResult.OK -> return true
                Sinks.EmitResult.FAIL_NON_SERIALIZED -> Thread.onSpinWait()
                else -> return false
            }
        }
        return false
    }

    /** Ends the outbound stream so the handler's `send` completes and the socket closes. */
    fun complete() {
        sink.tryEmitComplete()
    }

    /** Fails the outbound stream, which closes the socket with an overload status. */
    fun terminateOverloaded() {
        sink.tryEmitError(OutboundOverflowException(sessionId))
    }
}
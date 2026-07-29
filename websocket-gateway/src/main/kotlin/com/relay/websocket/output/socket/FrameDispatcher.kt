package com.relay.websocket.output.socket

import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.RelaySession
import com.relay.websocket.session.SessionRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The single dispatch seam: everything that pushes a frame to a client goes through here, so
 * a future transport change (per-node Redis channels instead of local-only delivery — Pattern C
 * of ARCHITECTURE.md §14.3) is a change to this class, not to every call site.
 *
 * Recipients that have no local session are simply not connected — their client catches up
 * over REST on reconnect, since a live push channel is not a delivery guarantee (Principle 1).
 */
@Component
class FrameDispatcher(
    private val registry: SessionRegistry
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Delivers to every session of every recipient. Returns the number of sockets queued to. */
    fun deliverToUsers(recipientIds: Collection<String>, frame: OutboundFrame): Int {
        if (recipientIds.isEmpty()) {
            logger.warn("Dropping {} with no recipients", frame::class.simpleName)
            return 0
        }
        var delivered = 0
        registry.sessionsOf(recipientIds).forEach { session ->
            if (push(session, frame)) delivered++
        }
        logger.debug("Dispatched {} to {} session(s)", frame::class.simpleName, delivered)
        return delivered
    }

    /**
     * Delivers to one specific connection — the device that issued a send gets its ack or error
     * here, not on the user's other devices. Sessions on other nodes are out of reach by design
     * until the per-node channel exists; false means "not held locally".
     */
    fun deliverToSession(userId: String, sessionId: String, frame: OutboundFrame): Boolean {
        val session = registry.sessionsOf(userId).firstOrNull { it.sessionId == sessionId }
        if (session == null) {
            logger.debug("Session {} of user {} is not on this node, dropping {}",
                sessionId, userId, frame::class.simpleName)
            return false
        }
        return push(session, frame)
    }

    /** Same as [deliverToUsers], but skips one session (the one that gets an ack instead). */
    fun deliverToUsersExcept(
        recipientIds: Collection<String>,
        excludedSessionIds: Set<String?>,
        frame: OutboundFrame
    ): Int {
        var delivered = 0
        registry.sessionsOf(recipientIds).forEach { session ->
            if (!excludedSessionIds.contains(session.sessionId) && push(session, frame)) delivered++
        }
        logger.debug("Dispatched {} to {} session(s)", frame::class.simpleName, delivered)
        return delivered
    }

    private fun push(session: RelaySession, frame: OutboundFrame): Boolean =
        if (session.send(frame)) {
            true
        } else {
            // The client is not draining its socket. Closing it is deliberate: the alternative
            // is buffering without bound on the gateway's heap (§9.5).
            logger.warn(
                "Session {} for user {} overflowed its outbound buffer, closing",
                session.sessionId, session.userId
            )
            session.terminateOverloaded()
            false
        }
}
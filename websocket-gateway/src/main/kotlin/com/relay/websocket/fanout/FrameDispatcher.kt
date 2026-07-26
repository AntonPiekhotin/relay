package com.relay.websocket.fanout

import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.SessionRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The single place that turns "these users should see this frame" into writes on live sockets.
 * Recipients that have no session are simply not connected — their client catches up over REST
 * on reconnect, since a live push channel is not a delivery guarantee.
 */
@Component
class FrameDispatcher(
    private val registry: SessionRegistry
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Returns the number of sockets the frame was queued to. */
    fun dispatch(recipientIds: Collection<String>, frame: OutboundFrame): Int {
        if (recipientIds.isEmpty()) {
            logger.warn("Dropping {} with no recipients", frame::class.simpleName)
            return 0
        }
        var delivered = 0
        registry.sessionsOf(recipientIds).forEach { session ->
            if (session.send(frame)) {
                delivered++
            } else {
                // The client is not draining its socket. Closing it is deliberate: the
                // alternative is buffering without bound on the gateway's heap.
                logger.warn(
                    "Session {} for user {} overflowed its outbound buffer, closing",
                    session.sessionId,
                    session.userId
                )
                session.terminateOverloaded()
            }
        }
        logger.debug("Dispatched {} to {} session(s)", frame::class.simpleName, delivered)
        return delivered
    }
}
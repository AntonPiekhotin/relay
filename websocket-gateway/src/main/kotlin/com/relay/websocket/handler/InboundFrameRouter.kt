package com.relay.websocket.handler

import com.relay.websocket.protocol.ErrorCode
import com.relay.websocket.protocol.FrameCodec
import com.relay.websocket.protocol.InboundFrame
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.session.RelaySession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Dispatches one inbound frame. Returns `Mono<Void>` because later phases do real work here
 * (a call to message-service on MESSAGE_SEND); for now everything resolves synchronously.
 *
 * A frame the gateway cannot handle produces an ERROR frame rather than closing the socket:
 * one bad frame should not cost the client its connection.
 */
@Component
class InboundFrameRouter(
    private val codec: FrameCodec
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun route(session: RelaySession, raw: String): Mono<Void> {
        val frame = try {
            codec.decode(raw)
        } catch (ex: Exception) {
            logger.debug("Rejected unparseable frame from session {}", session.sessionId, ex)
            session.send(OutboundFrame.Error(ErrorCode.BAD_FRAME, "Frame could not be parsed"))
            return Mono.empty()
        }
        return when (frame) {
            is InboundFrame.Ping -> {
                session.send(OutboundFrame.Pong(frame.nonce))
                Mono.empty()
            }
        }
    }
}
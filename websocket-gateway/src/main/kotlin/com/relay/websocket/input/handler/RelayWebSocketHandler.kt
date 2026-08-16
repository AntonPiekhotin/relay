package com.relay.websocket.input.handler

import com.relay.common.model.UserPrincipal
import com.relay.websocket.presence.PresenceService
import com.relay.websocket.protocol.ACCESS_TOKEN_PROTOCOL
import com.relay.websocket.protocol.FrameCodec
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.security.JwtUserPrincipalMapper
import com.relay.websocket.session.RelaySession
import com.relay.websocket.session.SessionRegistry
import com.relay.websocket.util.WebSocketProperties
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.SubProtocolCapable
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler

@Component
class RelayWebSocketHandler(
    private val registry: SessionRegistry,
    private val router: InboundFrameRouter,
    private val codec: FrameCodec,
    private val principalMapper: JwtUserPrincipalMapper,
    private val presenceService: PresenceService,
    private val props: WebSocketProperties
) : TextWebSocketHandler(), SubProtocolCapable {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Live sessions keyed by the container's session id, so inbound frames find their [RelaySession]. */
    private val sessions = ConcurrentHashMap<String, RelaySession>()

    /**
     * Declaring the subprotocol matters: the client requests `access_token`, and browsers abort
     * the connection unless the server confirms one of the protocols they asked for.
     */
    override fun getSubProtocols(): List<String> = listOf(ACCESS_TOKEN_PROTOCOL)

    /**
     * The principal is whatever Spring Security authenticated during the handshake — the security
     * filter chain rejects a bad or missing token with a 401 before the upgrade, so reaching this
     * method without one should not happen. It is still checked rather than asserted: an
     * unauthenticated socket must never be registered against a user.
     */
    override fun afterConnectionEstablished(socket: WebSocketSession) {
        val principal = (socket.principal as? Authentication)?.let(principalMapper::map)
        if (principal == null) {
            logger.warn("Closing session {}: no validated JWT on the WebSocket handshake", socket.id)
            closeQuietly(socket, CloseStatus.POLICY_VIOLATION)
            return
        }
        serve(socket, principal)
    }

    /**
     * `register` reports whether this was the user's *first* session, which is exactly when their
     * presence changed. A second device connecting is not an event anybody watching needs.
     */
    private fun serve(socket: WebSocketSession, principal: UserPrincipal) {
        val relaySession = RelaySession(socket.id, principal, props.outboundBufferSize)
        sessions[socket.id] = relaySession
        val cameOnline = registry.register(relaySession)
        relaySession.send(OutboundFrame.SessionConnected(principal.userId, socket.id))
        startWriter(socket, relaySession)
        if (cameOnline) presenceService.announceOnline(principal.userId)
    }

    /**
     * One virtual thread per connection, parked on the session's queue. This is the only thread
     * that ever touches [WebSocketSession.sendMessage], which the container does not allow to be
     * called concurrently — fan-out threads only ever hand frames to the queue.
     */
    private fun startWriter(socket: WebSocketSession, relaySession: RelaySession) {
        Thread.ofVirtual()
            .name("relay-ws-writer-${socket.id}")
            .start { pumpOutbound(socket, relaySession) }
    }

    private fun pumpOutbound(socket: WebSocketSession, relaySession: RelaySession) {
        try {
            while (true) {
                when (val next = relaySession.awaitOutbound()) {
                    is RelaySession.Outbound.Frame ->
                        socket.sendMessage(TextMessage(codec.encode(next.frame)))

                    RelaySession.Outbound.Completed -> {
                        closeQuietly(socket, CloseStatus.NORMAL)
                        return
                    }

                    // 1013 tells the client this is transient: reconnect and re-sync over REST.
                    RelaySession.Outbound.Overloaded -> {
                        logger.warn("Closing session {}: fell behind its outbound buffer", socket.id)
                        closeQuietly(socket, CloseStatus.SERVICE_OVERLOAD)
                        return
                    }
                }
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ex: Exception) {
            // A write that fails means the socket is gone. Closing it drives the container's
            // close callback, which unregisters the session.
            logger.debug("Write failed on session {}, closing", socket.id, ex)
            closeQuietly(socket, CloseStatus.SERVER_ERROR)
        }
    }

    /**
     * The container delivers a session's messages one at a time, so frames from one client are
     * still routed in order — what `concatMap` guaranteed on the reactive handler.
     */
    override fun handleTextMessage(socket: WebSocketSession, message: TextMessage) {
        val relaySession = sessions[socket.id] ?: return
        router.route(relaySession, message.payload)
    }

    override fun handleTransportError(socket: WebSocketSession, exception: Throwable) {
        logger.error("Transport error on session {}", socket.id, exception)
    }

    /**
     * Unregistering completes the outbound stream, which is what stops the writer thread — so a
     * socket that dies while its writer is parked does not leak the thread.
     *
     * Its presence subscriptions go first, so a session on its way out is not still on somebody's
     * fan-out list, and the offline announcement is made only when this was the user's *last*
     * session — which is what `unregister` decides.
     */
    override fun afterConnectionClosed(socket: WebSocketSession, status: CloseStatus) {
        val relaySession = sessions.remove(socket.id) ?: return
        presenceService.forget(relaySession)
        if (registry.unregister(relaySession)) {
            presenceService.announceOffline(relaySession.userId, Instant.now())
        }
        logger.debug(
            "Closed session {} for user {} ({})",
            socket.id, relaySession.userId, status
        )
    }

    /** Closing a socket the peer has already dropped throws; that is not worth propagating. */
    private fun closeQuietly(socket: WebSocketSession, status: CloseStatus) {
        runCatching { socket.close(status) }
            .onFailure { logger.trace("Ignoring close failure on session {}", socket.id, it) }
    }
}
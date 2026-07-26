package com.relay.websocket.handler

import com.relay.common.model.UserPrincipal
import com.relay.websocket.protocol.ACCESS_TOKEN_PROTOCOL
import com.relay.websocket.protocol.FrameCodec
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.security.JwtUserPrincipalMapper
import com.relay.websocket.session.OutboundOverflowException
import com.relay.websocket.session.RelaySession
import com.relay.websocket.session.SessionRegistry
import com.relay.websocket.util.WebSocketProperties
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono

private object UnauthenticatedSession :
    RuntimeException("No validated JWT on the WebSocket handshake")

@Component
class RelayWebSocketHandler(
    private val registry: SessionRegistry,
    private val router: InboundFrameRouter,
    private val codec: FrameCodec,
    private val principalMapper: JwtUserPrincipalMapper,
    private val props: WebSocketProperties
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Declaring the subprotocol matters: the client requests `access_token`, and browsers abort
     * the connection unless the server confirms one of the protocols they asked for.
     */
    override fun getSubProtocols(): List<String> = listOf(ACCESS_TOKEN_PROTOCOL)

    override fun handle(session: WebSocketSession): Mono<Void> =
        // Populated by Spring Security's SecurityContextServerWebExchangeWebFilter, which runs
        // after authentication and before the handler mapping. The empty-check belongs here and
        // not after `serve`, because a normally closed session also completes empty.
        session.handshakeInfo.principal
            .cast(Authentication::class.java)
            .switchIfEmpty(Mono.error(UnauthenticatedSession))
            .flatMap { authentication ->
                val principal = principalMapper.map(authentication)
                if (principal == null) Mono.error(UnauthenticatedSession)
                else serve(session, principal)
            }
            .onErrorResume { ex -> closeOnError(session, ex) }

    private fun serve(session: WebSocketSession, principal: UserPrincipal): Mono<Void> {
        val relaySession = RelaySession(session.id, principal, props.outboundBufferSize)
        registry.register(relaySession)
        relaySession.send(OutboundFrame.Connected(principal.userId, session.id))

        val outbound = session.send(relaySession.frames.map { session.textMessage(codec.encode(it)) })
        val inbound = session.receive()
            .map(WebSocketMessage::getPayloadAsText)
            .concatMap { raw -> router.route(relaySession, raw) }
            .then()

        // Whichever side finishes first tears the session down: the client closing ends
        // `receive()`, an outbound failure ends `send()`. Waiting for both would hang, because
        // the outbound sink only completes once the session is unregistered.
        return Mono.firstWithSignal(outbound, inbound)
            .doFinally { signal ->
                registry.unregister(relaySession)
                logger.debug("Closed session {} for user {} ({})", session.id, principal.userId, signal)
            }
    }

    private fun closeOnError(session: WebSocketSession, ex: Throwable): Mono<Void> = when {
        ex === UnauthenticatedSession -> {
            logger.warn("Closing session {}: {}", session.id, ex.message)
            session.close(CloseStatus.POLICY_VIOLATION)
        }
        // 1013 tells the client this is transient: reconnect and re-sync over REST.
        ex is OutboundOverflowException -> {
            logger.warn("Closing session {}: {}", session.id, ex.message)
            session.close(CloseStatus.SERVICE_OVERLOAD)
        }
        else -> {
            logger.error("Closing session {} after an unexpected failure", session.id, ex)
            session.close(CloseStatus.SERVER_ERROR)
        }
    }
}
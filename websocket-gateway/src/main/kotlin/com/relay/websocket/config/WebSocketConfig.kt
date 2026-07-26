package com.relay.websocket.config

import com.relay.websocket.handler.RelayWebSocketHandler
import com.relay.websocket.util.WebSocketProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter

@Configuration
class WebSocketConfig {

    @Bean
    fun webSocketHandlerMapping(
        handler: RelayWebSocketHandler,
        props: WebSocketProperties
    ): HandlerMapping =
        SimpleUrlHandlerMapping(mapOf(props.path to handler), Ordered.HIGHEST_PRECEDENCE)

    /**
     * Boot 4.1 has no WebSocket auto-configuration for WebFlux, so the adapter that turns a
     * matched [org.springframework.web.reactive.socket.WebSocketHandler] into an upgrade must be
     * declared explicitly.
     */
    @Bean
    fun webSocketHandlerAdapter(): WebSocketHandlerAdapter = WebSocketHandlerAdapter()
}
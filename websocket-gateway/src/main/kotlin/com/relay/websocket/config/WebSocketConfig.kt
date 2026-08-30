package com.relay.websocket.config

import com.relay.websocket.input.handler.RelayWebSocketHandler
import com.relay.websocket.util.WebSocketProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * Maps the handler onto the configured path. The subprotocol the server confirms comes from
 * [RelayWebSocketHandler] implementing [org.springframework.web.socket.SubProtocolCapable], which
 * the handshake handler reads to negotiate `access_token`.
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val handler: RelayWebSocketHandler,
    private val props: WebSocketProperties
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, props.path)
            .setAllowedOrigins(*props.allowedOrigins.toTypedArray())
    }
}
package com.relay.websocket.util

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "relay.websocket")
data class WebSocketProperties(

    val path: String = "/ws",

    /**
     * Max frames buffered per session before the socket is closed. A client that stops
     * reading must not be allowed to buffer without bound, so overflow is a hard close
     * and the client re-syncs over REST after reconnecting.
     */
    val outboundBufferSize: Int = 256,

    /** Keycloak client id whose roles are read out of the token's `resource_access` claim. */
    val clientId: String = "relay-client",

    val allowedOrigins: List<String> = listOf("*")
)
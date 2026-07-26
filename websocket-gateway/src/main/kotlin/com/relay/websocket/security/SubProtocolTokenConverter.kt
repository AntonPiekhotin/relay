package com.relay.websocket.security

import com.relay.websocket.protocol.ACCESS_TOKEN_PROTOCOL
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

private const val SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol"

/**
 * Browsers cannot set headers on a WebSocket handshake, so the token rides in the subprotocol
 * list as `access_token, <jwt>`. Returning empty leaves the exchange unauthenticated, which the
 * authorization rules then reject with a 401 before the upgrade happens.
 */
class SubProtocolTokenConverter : ServerAuthenticationConverter {

    override fun convert(exchange: ServerWebExchange): Mono<Authentication> =
        Mono.justOrEmpty(extractToken(exchange))
            .map { BearerTokenAuthenticationToken(it) }

    private fun extractToken(exchange: ServerWebExchange): String? {
        // The header may arrive as one comma-joined value or as repeated headers.
        val requested = exchange.request.headers[SEC_WEBSOCKET_PROTOCOL]
            ?.flatMap { it.split(",") }
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?: return null
        val marker = requested.indexOf(ACCESS_TOKEN_PROTOCOL)
        return if (marker < 0) null else requested.getOrNull(marker + 1)
    }
}
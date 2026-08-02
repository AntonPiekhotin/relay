package com.relay.websocket.security

import com.relay.websocket.protocol.ACCESS_TOKEN_PROTOCOL
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver

private const val SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol"

/**
 * Browsers cannot set headers on a WebSocket handshake, so the token rides in the subprotocol
 * list as `access_token, <jwt>`.
 *
 * Plugging this into the resource server as a [BearerTokenResolver] rather than hand-rolling an
 * authentication filter means the stock `BearerTokenAuthenticationFilter` does the work: a bad
 * token is rejected with a 401 before the upgrade, instead of opening a socket only to close it
 * again. Returning null leaves the request unauthenticated, which the authorization rules then
 * reject the same way.
 */
class SubProtocolTokenConverter : BearerTokenResolver {

    override fun resolve(request: HttpServletRequest): String? {
        // The header may arrive as one comma-joined value or as repeated headers.
        val requested = request.getHeaders(SEC_WEBSOCKET_PROTOCOL)
            ?.toList()
            ?.flatMap { it.split(",") }
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?: return null
        val marker = requested.indexOf(ACCESS_TOKEN_PROTOCOL)
        return if (marker < 0) null else requested.getOrNull(marker + 1)
    }
}
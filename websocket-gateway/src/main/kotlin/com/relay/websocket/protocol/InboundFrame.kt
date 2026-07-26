package com.relay.websocket.protocol

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * The subprotocol name the client must send first in `Sec-WebSocket-Protocol`, followed by
 * the access token: `new WebSocket(url, ["access_token", jwt])`. Keeping the token out of the
 * URL keeps it out of access logs and browser history.
 */
const val ACCESS_TOKEN_PROTOCOL = "access_token"

/**
 * Client to gateway. Adding a subtype here forces every `when` over this hierarchy to handle
 * it, which is how later phases pick up new commands without silently dropping them.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = InboundFrame.Ping::class, name = "PING")
)
sealed interface InboundFrame {

    data class Ping(val nonce: String? = null) : InboundFrame
}
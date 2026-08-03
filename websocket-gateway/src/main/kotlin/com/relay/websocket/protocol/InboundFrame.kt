package com.relay.websocket.protocol

/**
 * The subprotocol name the client must send first in `Sec-WebSocket-Protocol`, followed by
 * the access token: `new WebSocket(url, ["access_token", jwt])`. Keeping the token out of the
 * URL keeps it out of access logs and browser history.
 */
const val ACCESS_TOKEN_PROTOCOL = "access_token"

/**
 * Client to gateway, already unwrapped from the envelope by [FrameCodec]. [id] is the envelope
 * `id` — client-generated, used for correlation and idempotency; [ts] is the client timestamp,
 * advisory only.
 *
 * Adding a subtype forces every `when` over this hierarchy to handle it, which is how later
 * phases pick up new commands without silently dropping them.
 */
sealed interface InboundFrame {

    val id: String?
    val ts: Long?

    data class Ping(
        override val id: String? = null,
        override val ts: Long? = null
    ) : InboundFrame

    /**
     * Note the absence of a sender: it is taken from the authenticated session, never from the
     * frame, so a client cannot send as somebody else. The envelope [id] is the
     * client-generated message id that makes the send idempotent.
     */
    data class MessageSend(
        override val id: String,
        override val ts: Long? = null,
        val dialogId: String,
        val text: String
    ) : InboundFrame
}
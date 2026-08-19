package com.relay.call.output.sfu

import com.relay.call.service.sfu.RoomToken
import com.relay.call.service.sfu.RoomTokenFactory

import com.relay.call.config.CallProperties
import io.livekit.server.AccessToken
import io.livekit.server.CanPublish
import io.livekit.server.CanSubscribe
import io.livekit.server.RoomJoin
import io.livekit.server.RoomName
import java.time.Instant
import org.springframework.stereotype.Component

/**
 * Mints LiveKit room tokens. Offline: this signs a JWT with the shared API secret and never opens
 * a connection, so it is safe inside a request and needs no LiveKit server to be running — the
 * token is only *checked* when the client presents it to the SFU.
 *
 * The TTL is short on purpose. It bounds how long a leaked token admits someone, not how long they
 * may stay: LiveKit checks it at connection time only, and a client that needs to reconnect later
 * re-joins over REST and gets a fresh one.
 */
@Component
class LivekitTokenFactory(
    private val properties: CallProperties
) : RoomTokenFactory {

    override fun joinToken(room: String, identity: String): RoomToken {
        val livekit = properties.livekit
        val expiresAt = Instant.now().plus(livekit.tokenTtl)
        val token = AccessToken(livekit.apiKey, livekit.apiSecret).apply {
            this.identity = identity
            this.ttl = livekit.tokenTtl.toMillis()
            addGrants(RoomJoin(true), RoomName(room), CanPublish(true), CanSubscribe(true))
        }
        return RoomToken(url = livekit.url, token = token.toJwt(), expiresAt = expiresAt)
    }
}

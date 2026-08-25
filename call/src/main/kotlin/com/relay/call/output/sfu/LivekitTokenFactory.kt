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

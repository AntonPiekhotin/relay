package com.relay.call.service

import com.relay.call.config.CallProperties
import com.relay.call.model.dto.IceServer
import com.relay.call.model.dto.IceServersResponse
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Service

/**
 * Hands a client the ICE servers it needs to build a peer connection.
 *
 * TURN credentials are minted per request and expire, following coturn's `use-auth-secret` scheme
 * (`draft-uberti-behave-turn-rest-00`): the username is `<expiry-unix-seconds>:<userId>` and the
 * password is an HMAC of it under a secret shared with the TURN server only. Nothing is stored, and
 * the server needs no per-user TURN account.
 *
 * The alternative — one long-lived shared password — is how relays end up being used by strangers
 * to proxy traffic. A leaked credential here stops working on its own.
 */
@Service
class TurnCredentialService(
    private val properties: CallProperties
) {

    fun iceServersFor(userId: String): IceServersResponse {
        val turn = properties.turn
        val expiresAt = Instant.now().plus(turn.credentialTtl).epochSecond
        val username = "$expiresAt:$userId"
        val credential = sign(username, turn.staticAuthSecret)

        val (stunUrls, turnUrls) = turn.urls.partition { it.startsWith("stun:") }
        val servers = buildList {
            if (stunUrls.isNotEmpty()) add(IceServer(urls = stunUrls))
            if (turnUrls.isNotEmpty()) add(IceServer(urls = turnUrls, username = username, credential = credential))
        }
        return IceServersResponse(iceServers = servers, ttlSeconds = turn.credentialTtl.seconds)
    }

    private fun sign(username: String, secret: String): String {
        val mac = Mac.getInstance(HMAC_SHA1).apply {
            init(SecretKeySpec(secret.toByteArray(), HMAC_SHA1))
        }
        return Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray()))
    }

    private companion object {
        /** Fixed by the TURN REST scheme, not a choice. coturn verifies exactly this. */
        const val HMAC_SHA1 = "HmacSHA1"
    }
}

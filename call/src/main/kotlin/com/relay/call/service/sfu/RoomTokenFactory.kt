package com.relay.call.service.sfu

import java.time.Instant

/** What a client needs to enter an SFU room: where, and a short-lived proof it may. */
data class RoomToken(
    val url: String,
    val token: String,
    val expiresAt: Instant
)

/**
 * Mints SFU room-access tokens. A port so tests need no SFU: the LiveKit implementation signs the
 * JWT locally and never touches the network, but the seam keeps the SDK out of the service layer.
 *
 * Same shape as [com.relay.call.service.TurnCredentialService]: short-lived, server-signed,
 * per-user, and the secret never leaves the server.
 */
interface RoomTokenFactory {

    /** A token admitting [identity] to [room]. Fresh on every call — joining again refreshes it. */
    fun joinToken(room: String, identity: String): RoomToken
}

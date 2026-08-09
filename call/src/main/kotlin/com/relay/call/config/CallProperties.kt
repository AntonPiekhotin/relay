package com.relay.call.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "relay.call")
data class CallProperties(

    /**
     * How long a call rings before the server calls it missed. Clients cannot be trusted to report
     * an outcome for a call they never answered — the app was killed, the phone died — so the
     * server decides.
     */
    val ringTimeout: Duration = Duration.ofSeconds(40),

    /** Sweeper period. Adds to [ringTimeout] as jitter, so keep the sum inside 30-45s. */
    val sweepInterval: Duration = Duration.ofSeconds(5),

    /**
     * How long a candidate for an unknown call is held before being dropped. Trickle ICE can
     * outrun its own offer; discarding those candidates breaks call setup.
     */
    val iceBufferTtl: Duration = Duration.ofSeconds(5),

    val historyPageSize: Int = 50,

    val maxHistoryPageSize: Int = 100,

    val turn: Turn = Turn()
) {

    data class Turn(

        val urls: List<String> = listOf("stun:localhost:3478"),

        /**
         * Shared with coturn's `static-auth-secret`. It never leaves the server: clients receive a
         * short-lived HMAC of a username instead, so a leaked credential expires on its own rather
         * than handing out a permanent open relay.
         */
        val staticAuthSecret: String = "relay-turn-dev-secret",

        val credentialTtl: Duration = Duration.ofHours(12)
    )
}

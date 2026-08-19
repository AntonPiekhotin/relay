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

    /**
     * How often reconciliation compares each live group call against the SFU's own view of the
     * room. It is the backstop for a missed webhook and for a token that was minted but never
     * used, so its period is the ceiling on how long a vanished participant stays "busy".
     */
    val reconcileInterval: Duration = Duration.ofSeconds(30),

    /**
     * How long a freshly joined participant may be absent from the room before reconciliation
     * treats them as gone. They claimed over REST first and connect to the SFU second, so for a
     * moment "joined in the database, absent from the room" is the normal case, not a leak.
     */
    val reconcileGrace: Duration = Duration.ofSeconds(30),

    val turn: Turn = Turn(),

    val livekit: Livekit = Livekit(),

    val group: Group = Group()
) {

    data class Livekit(

        /** Where *clients* connect. Handed out verbatim in every group-call response. */
        val url: String = "ws://localhost:7880",

        /** Where *this service* calls the RoomService API and LiveKit posts webhooks from. */
        val apiUrl: String = "http://localhost:7880",

        val apiKey: String = "devkey",

        /**
         * Shared with the LiveKit container. Never leaves the server: clients receive a signed,
         * short-lived room token instead — the same doctrine as [Turn.staticAuthSecret]. LiveKit
         * refuses secrets shorter than 32 characters, hence the padded dev default.
         */
        val apiSecret: String = "relay-livekit-dev-secret-32-chars!!",

        /**
         * Admission-token lifetime. Checked at connection time only, so it bounds how long a
         * leaked token is usable, not how long a call may last.
         */
        val tokenTtl: Duration = Duration.ofMinutes(5)
    )

    data class Group(

        /** Initiator plus invitees. Input validation, not a race — the cap is not a constraint. */
        val maxParticipants: Int = 16
    )

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

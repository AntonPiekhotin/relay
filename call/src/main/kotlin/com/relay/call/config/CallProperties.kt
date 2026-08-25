package com.relay.call.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "relay.call")
data class CallProperties(
    val ringTimeout: Duration = Duration.ofSeconds(40),
    val sweepInterval: Duration = Duration.ofSeconds(5),
    val iceBufferTtl: Duration = Duration.ofSeconds(5),
    val historyPageSize: Int = 50,
    val maxHistoryPageSize: Int = 100,
    val reconcileInterval: Duration = Duration.ofSeconds(30),
    val reconcileGrace: Duration = Duration.ofSeconds(30),
    val turn: Turn = Turn(),
    val livekit: Livekit = Livekit(),
    val group: Group = Group()
) {

    data class Livekit(
        val url: String = "ws://localhost:7880",
        val apiUrl: String = "http://localhost:7880",
        val apiKey: String = "devkey",
        val apiSecret: String = "relay-livekit-dev-secret-32-chars!!",
        val tokenTtl: Duration = Duration.ofMinutes(5)
    )

    data class Group(
        val maxParticipants: Int = 16
    )

    data class Turn(
        val urls: List<String> = listOf("stun:localhost:3478"),
        val staticAuthSecret: String = "relay-turn-dev-secret",
        val credentialTtl: Duration = Duration.ofHours(12)
    )
}

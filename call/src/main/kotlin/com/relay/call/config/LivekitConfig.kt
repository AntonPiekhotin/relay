package com.relay.call.config

import io.livekit.server.RoomServiceClient
import io.livekit.server.WebhookReceiver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The two LiveKit SDK objects that need construction. Both are cheap and connectionless until
 * used: the [RoomServiceClient] is a lazy HTTP client, and the [WebhookReceiver] only verifies
 * signatures locally.
 */
@Configuration
class LivekitConfig {

    @Bean
    fun roomServiceClient(properties: CallProperties): RoomServiceClient =
        RoomServiceClient.createClient(
            host = properties.livekit.apiUrl,
            apiKey = properties.livekit.apiKey,
            secret = properties.livekit.apiSecret
        )

    @Bean
    fun webhookReceiver(properties: CallProperties): WebhookReceiver =
        WebhookReceiver(properties.livekit.apiKey, properties.livekit.apiSecret)
}

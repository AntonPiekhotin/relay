package com.relay.websocket.util

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "message-service")
data class MessageServiceProperties(

    /** Eureka service id, resolved through the load balanced WebClient. */
    val baseUrl: String = "lb://message",

    val messagesPath: String = "/internal/api/v1/messages",

    /**
     * Kept short: a client waiting on an ack cannot fall back to REST until this elapses, so a
     * slow message-service should surface as a failure quickly rather than stall the send.
     */
    val requestTimeout: Duration = Duration.ofSeconds(5)
)
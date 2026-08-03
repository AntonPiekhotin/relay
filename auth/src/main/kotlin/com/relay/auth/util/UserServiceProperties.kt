package com.relay.auth.util

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "user-service")
data class UserServiceProperties(
    /** Eureka service id, resolved through the load balanced [org.springframework.web.client.RestClient]. */
    val baseUrl: String,
    val usersPath: String,
    /** Budget for the response once the connection is up. */
    val requestTimeout: Duration = Duration.ofSeconds(5),
    /**
     * Budget for establishing the connection, kept short and separate: a host that is down or
     * unroutable should fail in a couple of seconds rather than spending the whole response
     * budget before the request is even on the wire.
     */
    val connectTimeout: Duration = Duration.ofSeconds(2)
)
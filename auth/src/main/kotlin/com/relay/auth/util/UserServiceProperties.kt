package com.relay.auth.util

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "user-service")
data class UserServiceProperties(
    /** Eureka service id, resolved through the load balanced [org.springframework.web.reactive.function.client.WebClient]. */
    val baseUrl: String,
    val usersPath: String,
    val requestTimeout: Duration = Duration.ofSeconds(5)
)
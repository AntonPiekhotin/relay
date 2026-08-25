package com.relay.auth.util

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "user-service")
data class UserServiceProperties(
    val baseUrl: String,
    val usersPath: String,
    val requestTimeout: Duration = Duration.ofSeconds(5),
    val connectTimeout: Duration = Duration.ofSeconds(2)
)
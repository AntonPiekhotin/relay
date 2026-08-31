package com.relay.call.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "relay.gateway-client")
data class GatewayClientProperties(

    val baseUrl: String = "lb://websocket-gateway",

    val connectTimeout: Duration = Duration.ofSeconds(2),

    val readTimeout: Duration = Duration.ofSeconds(3)
)

package com.relay.auth.util

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "keycloak")
data class KeycloakProperties(
    val url: String,
    val realm: String,
    val clientId: String,
    val clientSecret: String
)

package com.relay.notification.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * Unlike user/message (whose endpoints are `/internal` service-to-service), this service is
 * client-facing, so it validates the JWT itself against Keycloak's JWKS — the api-gateway's
 * check is not the only line of defence (ARCHITECTURE.md §8.3). The gateway forwards the
 * client's `Authorization: Bearer` header unchanged.
 */
@Configuration
@EnableWebSecurity
class NotificationSecurityConfig {

    @Bean
    fun chain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeHttpRequests { requests ->
                requests.anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 -> oauth2.jwt { } }
            .build()
}
package com.relay.message.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class MessageSecurityConfig {
    /**
     * Two audiences, two rules:
     *
     *  - everything under `/internal` is permitted because callers are other services, which have
     *    already authenticated the user. Those paths are not routed by the api-gateway, so they are
     *    only reachable service-to-service — and that omission is what makes this `permitAll` safe,
     *    since `SendMessageRequest.senderId` is taken at face value there.
     *  - everything under `/api/v1/message` is client-facing and validates the JWT here against
     *    Keycloak's JWKS. The gateway already checks it, but the gateway must not be the only line
     *    of defence — and the endpoints need the `sub` claim anyway to know who is asking.
     */
    @Bean
    fun chain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers("/internal/api/v1/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 -> oauth2.jwt { } }
            .build()
    /** Keeps Boot from auto-generating a default user with a random password. */
    @Bean
    fun noUserDetails(): UserDetailsService = InMemoryUserDetailsManager()
}
package com.relay.user.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class UserSecurityConfig {

    /**
     * Two audiences, two rules:
     *
     *  - everything under `/internal` is permitted because auth calls it during registration, at
     *    which point the user has no token yet. Those paths are not routed by the api-gateway, so
     *    they are only reachable service-to-service.
     *  - everything under `/api/v1/user` is client-facing and validates the JWT here against
     *    Keycloak's JWKS. The gateway already checks it, but the gateway must not be the only
     *    line of defence — and the endpoints need the `sub` claim anyway to know who "me" is.
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
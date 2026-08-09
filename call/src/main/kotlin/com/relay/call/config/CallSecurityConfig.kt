package com.relay.call.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * This service is the first with both surfaces, so the chain is the union of the two existing
 * shapes: `/internal` is permitted because its callers are other services that have already
 * authenticated the user, and `/api/v1/call` validates the JWT here rather than trusting the
 * api-gateway to be the only line of defence.
 *
 * The `/internal` routes trust a caller-supplied `userId`, so the api-gateway must never route
 * them. That omission is what makes this `permitAll` safe.
 */
@Configuration
@EnableWebSecurity
class CallSecurityConfig {

    @Bean
    fun chain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers("/internal/api/v1/**").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 -> oauth2.jwt { } }
            .build()
}

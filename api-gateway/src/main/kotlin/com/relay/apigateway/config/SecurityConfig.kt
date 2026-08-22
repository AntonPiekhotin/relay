package com.relay.apigateway.config


import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

    /**
     * Not named `springSecurityFilterChain`: on the servlet stack that name is already taken by the
     * `FilterChainProxy` that `WebSecurityConfiguration` registers, and reusing it fails the context.
     */
    @Bean
    fun gatewaySecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            .csrf { it.disable() }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 -> oauth2.jwt { } }
            .build()
    }
}
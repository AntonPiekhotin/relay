package com.relay.apigateway.config


import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers("/actuator/**").permitAll()
                    .pathMatchers("/api/v1/auth/**").permitAll()
                    .anyExchange().authenticated()
            }
            .oauth2ResourceServer { oauth2 -> oauth2.jwt { } }
            .build()
    }
}

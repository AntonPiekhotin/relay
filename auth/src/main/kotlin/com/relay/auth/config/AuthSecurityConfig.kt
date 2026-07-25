package com.relay.auth.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
class AuthSecurityConfig {

    @Bean
    fun chain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeExchange { exchange ->
                exchange
                    .pathMatchers("/api/v1/auth/**").permitAll()
                    .anyExchange().authenticated()
            }
            .build()

    @Bean
    fun noUserDetails(): ReactiveUserDetailsService {
        return MapReactiveUserDetailsService(emptyMap())
    }
}

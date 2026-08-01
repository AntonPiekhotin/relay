package com.relay.auth.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
class AuthSecurityConfig {

    /**
     * Register, login, refresh and logout are open by necessity — they are how a caller gets a token
     * in the first place. Changing a password is not: it acts on an existing account, so it is
     * matched *before* the open rule (the first matching rule wins) and requires a validated JWT,
     * which is also where the endpoint learns whose password to change.
     */
    @Bean
    fun chain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeExchange { exchange ->
                exchange
                    .pathMatchers(HttpMethod.POST, "/api/v1/auth/password").authenticated()
                    .pathMatchers("/api/v1/auth/**").permitAll()
                    .anyExchange().authenticated()
            }
            .oauth2ResourceServer { oauth2 -> oauth2.jwt { } }
            .build()

    @Bean
    fun noUserDetails(): ReactiveUserDetailsService {
        return MapReactiveUserDetailsService(emptyMap())
    }
}

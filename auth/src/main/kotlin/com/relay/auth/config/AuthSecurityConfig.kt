package com.relay.auth.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class AuthSecurityConfig {

    /**
     * Register, login, refresh and logout are open by necessity — they are how a caller gets a token
     * in the first place. Changing a password is not: it acts on an existing account, so it is
     * matched *before* the open rule (the first matching rule wins) and requires a validated JWT,
     * which is also where the endpoint learns whose password to change.
     */
    @Bean
    fun chain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/password").authenticated()
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 -> oauth2.jwt { } }
            .build()

    /** Empty on purpose: this service authenticates by JWT only, and without a bean here Boot
     *  would auto-configure a default user with a generated password. */
    @Bean
    fun noUserDetails(): UserDetailsService = InMemoryUserDetailsManager()
}
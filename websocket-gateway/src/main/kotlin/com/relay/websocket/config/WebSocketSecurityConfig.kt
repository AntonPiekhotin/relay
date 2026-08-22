package com.relay.websocket.config

import com.relay.websocket.security.SubProtocolTokenConverter
import com.relay.websocket.util.WebSocketProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class WebSocketSecurityConfig(
    private val props: WebSocketProperties
) {

    /**
     * Authenticating the handshake means a bad or missing token is rejected with a 401 before the
     * upgrade, instead of opening a socket only to close it again. The token is read out of the
     * `Sec-WebSocket-Protocol` header by [SubProtocolTokenConverter], because browsers cannot set
     * an `Authorization` header on a WebSocket handshake.
     *
     * `JwtDecoder` is auto-configured from
     * `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, so the gateway validates Keycloak
     * tokens itself — it sits outside the api-gateway and cannot inherit its checks.
     */
    @Bean
    fun chain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            // Every handshake carries its own token; there is no login session to keep.
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(props.path).authenticated()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .anyRequest().denyAll()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2
                    .bearerTokenResolver(SubProtocolTokenConverter())
                    .jwt { }
            }
            .build()
}
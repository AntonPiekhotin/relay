package com.relay.websocket.config

import com.relay.websocket.security.SubProtocolTokenConverter
import com.relay.websocket.util.WebSocketProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.AuthenticationWebFilter
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers

@Configuration
@EnableWebFluxSecurity
class WebSocketSecurityConfig(
    private val props: WebSocketProperties
) {

    /**
     * Authenticating the handshake means a bad or missing token is rejected with a 401 before the
     * upgrade, instead of opening a socket only to close it again.
     *
     * [ReactiveJwtDecoder] is auto-configured from
     * `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, so the gateway validates Keycloak
     * tokens itself — it sits outside the api-gateway and cannot inherit its checks.
     */
    @Bean
    fun chain(http: ServerHttpSecurity, jwtDecoder: ReactiveJwtDecoder): SecurityWebFilterChain {
        val handshakeAuthentication =
            AuthenticationWebFilter(JwtReactiveAuthenticationManager(jwtDecoder)).apply {
                setServerAuthenticationConverter(SubProtocolTokenConverter())
                setRequiresAuthenticationMatcher(ServerWebExchangeMatchers.pathMatchers(props.path))
            }
        return http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .addFilterAt(handshakeAuthentication, SecurityWebFiltersOrder.AUTHENTICATION)
            .authorizeExchange { exchange ->
                exchange
                    .pathMatchers(props.path).authenticated()
                    .anyExchange().denyAll()
            }
            .build()
    }
}
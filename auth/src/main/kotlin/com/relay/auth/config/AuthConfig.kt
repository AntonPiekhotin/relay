package com.relay.auth.config

import com.relay.auth.util.KeycloakProperties
import org.keycloak.OAuth2Constants
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.KeycloakBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class KeycloakConfig(
    private val props: KeycloakProperties,
) {

    @Bean
    fun keycloak(): Keycloak {
        return KeycloakBuilder.builder()
            .serverUrl(props.url)
            .realm(props.realm)
            .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
            .clientId(props.clientId)
            .clientSecret(props.clientSecret)
            .build()
    }

    @Bean
    fun webClient(): WebClient =
        WebClient.builder()
            .baseUrl(props.url)
            .build()
}

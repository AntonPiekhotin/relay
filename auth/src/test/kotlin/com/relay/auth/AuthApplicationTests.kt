package com.relay.auth

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * Nothing in this service reaches out at startup: the Keycloak admin client authenticates lazily on
 * its first call, and the JWT decoder fetches JWKS only when a token arrives. So the context starts
 * with neither Keycloak nor Eureka running.
 */
@SpringBootTest(properties = ["eureka.client.enabled=false"])
class AuthApplicationTests {

    @Test
    fun contextLoads() {
    }
}

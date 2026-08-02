package com.relay.user

import org.junit.jupiter.api.Test

/**
 * The context starts offline: the JWT decoder is lazy (no JWKS fetch until the first token
 * arrives), so being a resource server does not stop it, and Eureka is off.
 *
 * Sharing [UserServiceIntegrationTest] rather than declaring its own properties is deliberate —
 * an identical configuration means this reuses the cached context instead of booting a second one
 * just to assert that booting works.
 */
@UserServiceIntegrationTest
class UserServiceApplicationTests {

    @Test
    fun contextLoads() {
    }
}
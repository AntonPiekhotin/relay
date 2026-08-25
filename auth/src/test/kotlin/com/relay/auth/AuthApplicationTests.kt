package com.relay.auth

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["eureka.client.enabled=false"])
class AuthApplicationTests {

    @Test
    fun contextLoads() {
    }
}

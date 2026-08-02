package com.relay.notification

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Postgres comes from [PostgresTestcontainerConfig] and the schema from Flyway, so this also
 * asserts that the migrations and the entities agree. The JWT decoder is lazy (no JWKS fetch until
 * the first token arrives), and Kafka listener containers retry in the background — neither fails
 * context startup.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "relay.push.fcm.enabled=false"
    ]
)
@Import(PostgresTestcontainerConfig::class)
class NotificationApplicationTests {

    @Test
    fun contextLoads() {
    }
}
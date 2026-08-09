package com.relay.call

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Boots the whole service against a real Postgres, which is what makes this more than a smoke test:
 * Flyway builds the schema and `ddl-auto: validate` then refuses to start if any entity has drifted
 * from it.
 *
 * Eureka is off because there is no registry in a test, and the Kafka producer needs no substitute —
 * nothing publishes during startup.
 */
@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Import(PostgresTestcontainerConfig::class)
class CallApplicationTests {

    @Test
    fun contextLoads() {
    }
}

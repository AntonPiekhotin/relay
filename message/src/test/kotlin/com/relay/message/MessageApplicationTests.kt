package com.relay.message

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Postgres comes from [PostgresTestcontainerConfig] and the schema from Flyway, so this also
 * asserts that the migrations and the entities agree. Kafka needs no substitute here: listener
 * containers retry connecting in the background and do not fail context startup.
 */
@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Import(PostgresTestcontainerConfig::class)
class MessageApplicationTests {

    @Test
    fun contextLoads() {
    }
}
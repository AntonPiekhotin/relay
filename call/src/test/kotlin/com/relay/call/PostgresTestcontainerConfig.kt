package com.relay.call

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Postgres for the test suite, on the same image the compose stack runs.
 *
 * Because the migrations run on startup, every test run is also a test of the migrations, and
 * `ddl-auto: validate` makes each one assert that the entities still match what they produce —
 * which is the only thing standing between the `calls` table and an entity that drifted from it.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestcontainerConfig {

    // `destroyMethod = ""` keeps Spring from stopping the container when a context closes — the
    // suite has more than one context, and the first to shut down would otherwise take the
    // database out from under the rest. Testcontainers' own reaper handles it at JVM exit.
    @Bean(destroyMethod = "")
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<Nothing> = CONTAINER

    companion object {
        /** One container for the whole JVM rather than one per Spring context. */
        private val CONTAINER: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:15").apply { start() }
    }
}

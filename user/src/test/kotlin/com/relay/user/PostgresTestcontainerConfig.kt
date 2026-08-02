package com.relay.user

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Postgres for the test suite, on the same image the compose stack runs.
 *
 * H2 used to stand in here, which stopped working the moment Flyway owned the schema: the
 * migrations are Postgres SQL (`bytea`, `timestamp with time zone`), and running the suite against
 * a different engine would have verified a schema no environment actually has. The trade is that
 * tests now need Docker.
 *
 * Because the migrations run on startup, every test run is also a test of the migrations, and
 * `ddl-auto: validate` makes each one assert that the entities still match what they produce.
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
        /**
         * One container for the whole JVM rather than one per Spring context. Starting Postgres
         * costs a second or two; paying that per context is the difference between a suite that
         * runs and one nobody waits for.
         */
        private val CONTAINER: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:15").apply { start() }
    }
}
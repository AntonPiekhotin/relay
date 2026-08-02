package com.relay.user

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Shared wiring for the service-level integration tests: a Testcontainers Postgres in place of the
 * compose stack, schema built by Flyway, and no Eureka.
 *
 * Declared once as a meta-annotation so every test class asks for the *identical* configuration —
 * Spring caches contexts by that, and one differing string means a second application boot on top
 * of a second schema migration.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Import(PostgresTestcontainerConfig::class)
annotation class UserServiceIntegrationTest
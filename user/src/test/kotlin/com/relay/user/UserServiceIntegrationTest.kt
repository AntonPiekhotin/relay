package com.relay.user

import org.springframework.boot.test.context.SpringBootTest

/**
 * Shared wiring for the service-level integration tests: H2 in place of Postgres and no Eureka, so
 * the suite runs with nothing else started. Declared once as a meta-annotation so every test class
 * asks for the *identical* property set — Spring caches contexts by that set, and one differing
 * string means a second application boot.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:userit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
    ]
)
annotation class UserServiceIntegrationTest

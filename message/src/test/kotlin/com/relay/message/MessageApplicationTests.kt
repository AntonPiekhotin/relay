package com.relay.message

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * H2 stands in for Postgres so the suite runs without the compose stack. Kafka needs no
 * substitute here: listener containers retry connecting in the background and do not fail
 * context startup.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ctxdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
    ]
)
class MessageApplicationTests {

    @Test
    fun contextLoads() {
    }
}
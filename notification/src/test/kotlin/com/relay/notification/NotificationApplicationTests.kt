package com.relay.notification

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * H2 stands in for Postgres so the suite runs without the compose stack. The JWT decoder is
 * lazy (no JWKS fetch until the first token arrives), and Kafka listener containers retry in
 * the background — neither fails context startup.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "relay.push.fcm.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:notifctx;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
    ]
)
class NotificationApplicationTests {

    @Test
    fun contextLoads() {
    }
}
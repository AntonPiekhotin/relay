package com.relay.user

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * H2 stands in for Postgres so the suite runs without the compose stack. The JWT decoder is lazy
 * (no JWKS fetch until the first token arrives), so being a resource server does not stop the
 * context from starting offline.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:userctx;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
    ]
)
class UserServiceApplicationTests {

    @Test
    fun contextLoads() {
    }
}

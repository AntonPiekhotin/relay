package com.relay.websocket.util

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where call-service is and how long to wait for it.
 *
 * **Both timeouts are set on purpose.** A blocking HTTP client needs a connect timeout *and* a
 * response timeout: one value covered the whole call on the reactive stack, but here setting only
 * the read timeout leaves an unroutable host parking the calling thread until the OS gives up
 * (`docs/CONCURRENCY.md` §4). Virtual threads make blocking cheap, not free — an unbounded wait is
 * a socket that never gets its error frame.
 *
 * They are short because this is the call setup path. A signal that takes three seconds has already
 * failed as far as the user is concerned.
 */
@ConfigurationProperties(prefix = "relay.call-client")
data class CallClientProperties(

    /** Resolved through Eureka. Overridden in tests to point at a stub. */
    val baseUrl: String = "lb://call",

    val connectTimeout: Duration = Duration.ofSeconds(2),

    val readTimeout: Duration = Duration.ofSeconds(3)
)

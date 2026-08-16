package com.relay.websocket.util

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where message-service is, and how long the gateway may believe what it said about who is in a
 * dialog.
 *
 * Timeouts are not here: this client shares the load-balanced builder configured from
 * [CallClientProperties], because both paths want the same short bounds and a second builder bean
 * would need qualifiers everywhere.
 */
@ConfigurationProperties(prefix = "relay.message-client")
data class MessageClientProperties(

    /** Resolved through Eureka. Overridden in tests to point at a stub. */
    val baseUrl: String = "lb://message",

    /**
     * How long a resolved participant list is reused. Only a group dialog could ever change one,
     * and group dialogs do not exist yet — so this bounds staleness that cannot currently occur,
     * and is the knob to reach for when they land.
     */
    val membershipTtl: Duration = Duration.ofMinutes(5),

    /** Backstop on the cache's size. Over it, lookups stay uncached rather than evicting live rows. */
    val maxCachedDialogs: Int = 10_000
)

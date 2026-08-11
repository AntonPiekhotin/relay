package com.relay.message.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "relay.message")
data class MessageProperties(

    /** Page size when a client names none. Matches the documented default in `docs/PROTOCOL.md` §9. */
    val historyPageSize: Int = 50,

    /**
     * Ceiling on `limit`. Clamped rather than rejected, like the paged user endpoints: a client
     * asking for too much gets the maximum, not a `400` it has to special-case.
     */
    val maxHistoryPageSize: Int = 100
)

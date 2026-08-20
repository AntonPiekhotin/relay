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
    val maxHistoryPageSize: Int = 100,

    /**
     * Hard ceiling on a group's membership, enforced under the dialog row lock so concurrent adds
     * cannot slip past it. What it really bounds is fan-out: every send carries the full member
     * list on `messages.delivery`, and presence subscription answers one snapshot frame per member
     * against the gateway's 256-frame outbound buffer.
     */
    val groupMemberCap: Int = 50,

    /** Dialog-list page size when a client names none. */
    val dialogPageSize: Int = 100,

    /** Ceiling on the dialog-list `limit`, clamped like [maxHistoryPageSize]. */
    val maxDialogPageSize: Int = 100
)

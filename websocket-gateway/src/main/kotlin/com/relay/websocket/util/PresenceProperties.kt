package com.relay.websocket.util

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "relay.presence")
data class PresenceProperties(

    /**
     * How many users' last-seen timestamps this node keeps.
     *
     * Presence is never persisted (`docs/ARCHITECTURE.md` Principle 3), so last-seen lives in the
     * gateway's heap and nothing ever deletes a user. Unlike the session map — which drains as
     * people disconnect — this one only grows, so it needs a ceiling: over it, the oldest
     * timestamps are dropped, which is also the right thing to lose.
     */
    val maxLastSeenEntries: Int = 100_000
)

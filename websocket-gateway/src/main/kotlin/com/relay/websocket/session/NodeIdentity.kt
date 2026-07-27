package com.relay.websocket.session

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Stable identity of this gateway instance (ARCHITECTURE.md §9.2, Principle 5). Nothing reads
 * it yet — it exists now because it is a prerequisite for every future routing path (session
 * registry entries, per-node delivery channels), and threading it through later touches every
 * call site.
 *
 * Set `NODE_ID` in the environment for a stable name; otherwise a UUID is generated and held
 * for the process lifetime.
 */
@Component
class NodeIdentity(
    @Value("\${NODE_ID:}") configured: String
) {

    val id: String = configured.ifBlank { "ws-${UUID.randomUUID()}" }

    init {
        LoggerFactory.getLogger(javaClass).info("websocket-gateway node identity: {}", id)
    }
}
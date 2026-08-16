package com.relay.websocket.output.http

import com.relay.websocket.protocol.ErrorCodes
import com.relay.websocket.util.MessageClientProperties
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Membership lookups with a short-lived cache in front.
 *
 * The cache exists for typing, not for presence. A subscription resolves once when a conversation
 * opens, but a client emits `typing.start` every three seconds while somebody is writing — an HTTP
 * round trip per frame would make the cheapest, least important traffic class the chattiest thing
 * the gateway does.
 *
 * **Why a TTL is safe today:** a direct dialog's membership is immutable — the pair *is* the row's
 * identity (`dialogs.direct_key`), so there is nothing to invalidate. Group dialogs would change
 * that, and this is the class that has to grow an invalidation path when they land; the TTL is what
 * bounds the staleness until then.
 *
 * **Authorization survives the cache.** A `Found` answer from message-service already means "you are
 * in this dialog", so on a hit the same question is answered locally by looking for the caller in the
 * cached membership — which is the identical check, not a weaker one.
 */
@Component
class DialogMembershipResolver(
    private val client: DialogMembershipClient,
    private val properties: MessageClientProperties
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private class Entry(val participantIds: List<String>, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun resolve(dialogId: String, callerId: String): DialogMembershipResult {
        live(dialogId)?.let { participants ->
            return if (callerId in participants) {
                DialogMembershipResult.Found(participants)
            } else {
                // Exactly what the endpoint would have answered — see the class comment.
                DialogMembershipResult.Rejected(ErrorCodes.DIALOG_NOT_FOUND, "Dialog not found")
            }
        }
        return client.participants(dialogId, callerId).also { result ->
            if (result is DialogMembershipResult.Found) store(dialogId, result.participantIds)
        }
    }

    private fun live(dialogId: String): List<String>? {
        val entry = cache[dialogId] ?: return null
        if (entry.expiresAt <= Instant.now()) {
            cache.remove(dialogId, entry)
            return null
        }
        return entry.participantIds
    }

    /**
     * Bounded, and it fails open rather than evicting blind: over the cap, expired entries go first,
     * and if they were all still live the lookup simply stays uncached. Growth is by real dialogs the
     * caller belongs to — a stranger's dialog id never gets this far — so the cap is a backstop, not
     * a defence.
     */
    private fun store(dialogId: String, participantIds: List<String>) {
        if (cache.size >= properties.maxCachedDialogs) purgeExpired()
        if (cache.size >= properties.maxCachedDialogs) {
            logger.debug("Membership cache is full; not caching dialog {}", dialogId)
            return
        }
        cache[dialogId] = Entry(participantIds, Instant.now().plus(properties.membershipTtl))
    }

    private fun purgeExpired() {
        val now = Instant.now()
        cache.entries.removeIf { it.value.expiresAt <= now }
    }
}

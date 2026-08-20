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
 * **Invalidation and the TTL split the work.** A direct dialog's membership is immutable — the pair
 * *is* the row's identity (`dialogs.direct_key`) — so only group dialogs ever change one, and every
 * group change arrives here as a `GroupChanged` on `messages.delivery`, whose consumer calls
 * [invalidate]. That shrinks the staleness window from the TTL to the broker's consume lag. The TTL
 * stays as the backstop for the event that never arrives: the gateway consumes at `latest`, so a
 * change published while a node was down is invisible to it forever, and without the TTL a removed
 * member would keep presence and typing access on that node indefinitely.
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

    /**
     * Drops one dialog's cached membership — the invalidation path group dialogs required. This is
     * an *authorization* seam, not just a freshness one: the cached list answers "are you in this
     * dialog" for presence and typing, so a removed member's entry is a stale yes.
     *
     * Invalidate rather than re-prime from the event: an HTTP resolve already in flight when the
     * event arrives could overwrite a primed entry with the pre-change list it fetched, and the
     * event deliberately does not carry the membership. The residual race — a fetch that started
     * before the commit landing after this remove — is bounded by the TTL like everything else.
     */
    fun invalidate(dialogId: String) {
        cache.remove(dialogId)
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

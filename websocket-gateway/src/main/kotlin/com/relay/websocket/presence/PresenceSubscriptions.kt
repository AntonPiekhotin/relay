package com.relay.websocket.presence

import com.relay.websocket.session.RelaySession
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/**
 * Who is watching whose presence, on this node.
 *
 * Two views of the same relation, because both directions are hot: a user coming online needs the
 * sessions to notify, and a session closing needs the subjects to release. Neither may be a scan.
 *
 * Subscriptions are held per **dialog**, not as a flat set of subjects, so unsubscribing one
 * conversation cannot silently cancel presence a *different* open conversation still needs. That
 * cannot happen with direct dialogs — a peer appears in exactly one — but it is the kind of bug that
 * only shows up once group dialogs exist, by which time the subscription bookkeeping is nobody's
 * suspect.
 *
 * Every mutation of the inner per-session map happens inside a `compute` on that session's key, so
 * the map is only ever touched under that key's lock — see [InMemorySessionRegistry][com.relay
 * .websocket.session.InMemorySessionRegistry], which uses the same discipline for the same reason.
 *
 * **Node-local, like the session registry it derives from.** A second gateway node would see only
 * its own subscribers and its own connections, so it would report users held elsewhere as offline —
 * the same gap that blocks a second node today (`docs/ARCHITECTURE.md` §6).
 */
@Component
class PresenceSubscriptions {

    /** subject user id → sessions that asked to hear about them. */
    private val subscribersBySubject = ConcurrentHashMap<String, MutableSet<RelaySession>>()

    /** session id → dialog id → the subjects that dialog subscribed to. */
    private val dialogsBySession = ConcurrentHashMap<String, MutableMap<String, Set<String>>>()

    /** Re-subscribing the same dialog replaces its subjects rather than accumulating them. */
    fun subscribe(session: RelaySession, dialogId: String, subjects: Set<String>) {
        if (subjects.isEmpty()) return
        dialogsBySession.compute(session.sessionId) { _, existing ->
            (existing ?: mutableMapOf()).apply { put(dialogId, subjects) }
        }
        subjects.forEach { subject ->
            subscribersBySubject.compute(subject) { _, sessions ->
                (sessions ?: ConcurrentHashMap.newKeySet()).apply { add(session) }
            }
        }
    }

    /**
     * Drops this dialog's subscriptions, keeping any subject another of the session's open dialogs
     * still wants. Unknown dialog ids are ignored — a client unsubscribing twice is not an error.
     */
    fun unsubscribe(session: RelaySession, dialogId: String) {
        var released: Set<String> = emptySet()
        dialogsBySession.compute(session.sessionId) { _, existing ->
            if (existing == null) return@compute null
            val removed = existing.remove(dialogId)
            if (removed != null) released = removed - existing.values.flatten().toSet()
            existing.takeIf { it.isNotEmpty() }
        }
        released.forEach { drop(it, session) }
    }

    /**
     * The live sessions on this node that hold a subscription for [dialogId] — the group-change
     * fix-up path, which needs "who is watching this conversation" rather than "who is watching
     * this person". A scan of the session map is fine here: membership changes are orders of
     * magnitude rarer than the fan-outs the two indexed views exist for.
     */
    fun sessionsWithDialog(dialogId: String): List<RelaySession> {
        val sessionIds = dialogsBySession.entries
            .filter { dialogId in it.value }
            .map { it.key }
            .toSet()
        if (sessionIds.isEmpty()) return emptyList()
        return subscribersBySubject.values
            .flatten()
            .distinct()
            .filter { it.sessionId in sessionIds && !it.isEnding }
    }

    /** Everything this session subscribed to. Called when its socket closes. */
    fun forget(session: RelaySession) {
        var released: Set<String> = emptySet()
        dialogsBySession.compute(session.sessionId) { _, existing ->
            released = existing?.values?.flatten()?.toSet().orEmpty()
            null
        }
        released.forEach { drop(it, session) }
    }

    /**
     * The sessions to notify about [subject], pruning any that have already ended.
     *
     * The pruning is not housekeeping. A socket closing and a frame already in flight on it can
     * interleave, leaving a subscription behind for a session that will never be delivered to again;
     * dropping it on the next fan-out keeps that from being a permanent leak.
     */
    fun subscribersOf(subject: String): Collection<RelaySession> {
        val sessions = subscribersBySubject[subject]?.toList() ?: return emptyList()
        val (live, ended) = sessions.partition { !it.isEnding }
        ended.forEach { drop(subject, it) }
        return live
    }

    private fun drop(subject: String, session: RelaySession) {
        subscribersBySubject.compute(subject) { _, sessions ->
            // Returning null removes the key, so a user nobody watches leaves nothing behind.
            sessions?.apply { remove(session) }?.takeIf { it.isNotEmpty() }
        }
    }
}

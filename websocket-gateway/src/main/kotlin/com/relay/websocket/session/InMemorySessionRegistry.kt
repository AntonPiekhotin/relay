package com.relay.websocket.session

import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Correct for the single-instance MVP. Both mutators decide first/last-session inside the map's
 * atomic `compute`, so presence transitions cannot interleave with a concurrent connect or
 * disconnect for the same user.
 */
@Component
class InMemorySessionRegistry : SessionRegistry {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val sessionsByUser = ConcurrentHashMap<String, MutableSet<RelaySession>>()

    override fun register(session: RelaySession): Boolean {
        var cameOnline = false
        sessionsByUser.compute(session.userId) { _, existing ->
            cameOnline = existing.isNullOrEmpty()
            (existing ?: ConcurrentHashMap.newKeySet()).apply { add(session) }
        }
        logger.debug("Registered session {} for user {} (came online: {})", session.sessionId, session.userId, cameOnline)
        return cameOnline
    }

    override fun unregister(session: RelaySession): Boolean {
        var wentOffline = false
        sessionsByUser.compute(session.userId) { _, existing ->
            // Returning null removes the key, so an offline user leaves nothing behind.
            existing?.apply { remove(session) }?.takeIf { it.isNotEmpty() }
                .also { wentOffline = it == null }
        }
        session.complete()
        logger.debug("Removed session {} for user {} (went offline: {})", session.sessionId, session.userId, wentOffline)
        return wentOffline
    }

    override fun sessionsOf(userId: String): Collection<RelaySession> =
        sessionsByUser[userId]?.toList() ?: emptyList()

    override fun sessionsOf(userIds: Collection<String>): Collection<RelaySession> =
        userIds.distinct().flatMap(::sessionsOf)

    override fun isOnline(userId: String): Boolean = sessionsByUser.containsKey(userId)

    override fun onlineUserCount(): Int = sessionsByUser.size
}
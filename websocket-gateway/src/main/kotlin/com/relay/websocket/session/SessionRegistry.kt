package com.relay.websocket.session

/**
 * Where the gateway keeps its live connections. Deliberately an interface: the MVP runs one
 * instance and an in-memory map is correct, and the Redis-backed implementation for multiple
 * instances drops in here without touching the handlers.
 */
interface SessionRegistry {

    /** True when this was the user's first session, i.e. they just came online. */
    fun register(session: RelaySession): Boolean

    /** True when this was the user's last session, i.e. they just went offline. */
    fun unregister(session: RelaySession): Boolean

    fun sessionsOf(userId: String): Collection<RelaySession>

    fun sessionsOf(userIds: Collection<String>): Collection<RelaySession>

    fun isOnline(userId: String): Boolean

    fun onlineUserCount(): Int
}
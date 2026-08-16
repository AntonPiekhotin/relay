package com.relay.common.event

import java.time.Instant

/**
 * The two ephemeral relay events, both produced and consumed by websocket-gateway.
 *
 * They are the only events in this package whose producer and consumer are the same service, and
 * that is the point: the gateway publishes what one of its nodes observed so that *every* node can
 * serve the sockets it happens to hold. Without the hop, a presence transition on one node is
 * invisible to a subscriber connected to another.
 *
 * **Neither is durable in any useful sense.** The gateway consumes with
 * `auto-offset-reset: latest`, so a restarted node starts at the tail and never replays a stale
 * indicator; a lost event degrades to a missing dot or a missing indicator, which the next snapshot
 * or the client's own 5-second expiry corrects. Nothing here may be treated as a delivery guarantee.
 */

/**
 * One user's connection state changed. Keyed by [userId] on the wire, which is what keeps a user's
 * own transitions in one partition — an `offline` overtaking the `online` that followed it would
 * leave every subscriber showing a stale dot until the next transition.
 *
 * It carries **no recipient list**, unlike every other fan-out event here. Presence recipients are
 * whoever *subscribed* to this user, which is per-connection state each node holds privately; the
 * producing node does not know another node's subscribers and must not try to.
 *
 * [lastSeen] is stamped by the producer, not the consumer: it is when the user actually went away,
 * and a consumer's clock reading would drift by the queue latency. Null for [PresenceStatuses.ONLINE].
 */
data class PresenceEvent(
    val userId: String,
    val status: String,
    val lastSeen: Instant? = null
)

/** The `status` vocabulary of a [PresenceEvent]. Strings on the wire, like every other event field. */
object PresenceStatuses {
    const val ONLINE = "online"
    const val OFFLINE = "offline"
}

/**
 * Somebody started typing. Keyed by [dialogId], like every other dialog-scoped topic, so one
 * conversation's indicators stay ordered and different conversations spread across partitions.
 *
 * [recipientIds] is resolved by the **producing** node, from message-service, and already excludes
 * the typist — their own other devices must not be told they are typing. Membership resolution stays
 * server-side for the same reason a send's fan-out list does: an event that let a client name its own
 * recipients would let it push frames to anyone.
 *
 * There is no stop event, because there is no stop frame: the indicator expires client-side.
 */
data class TypingEvent(
    val dialogId: String,
    val userId: String,
    val recipientIds: List<String>
)

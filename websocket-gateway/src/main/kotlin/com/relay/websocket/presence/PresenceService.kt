package com.relay.websocket.presence

import com.relay.common.event.PresenceEvent
import com.relay.common.event.PresenceStatuses
import com.relay.common.event.TypingEvent
import com.relay.websocket.output.event.PresenceEventProducer
import com.relay.websocket.output.http.DialogMembershipResolver
import com.relay.websocket.output.http.DialogMembershipResult
import com.relay.websocket.output.socket.FrameDispatcher
import com.relay.websocket.protocol.OutboundFrame
import com.relay.websocket.protocol.PresenceStatus
import com.relay.websocket.session.RelaySession
import com.relay.websocket.session.SessionRegistry
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Outcome of a `presence.subscribe`. [Rejected] becomes the client's `error` frame. */
sealed interface PresenceSubscribeResult {

    data object Subscribed : PresenceSubscribeResult

    data class Rejected(val code: String, val message: String) : PresenceSubscribeResult
}

/**
 * Presence and typing. Two halves that never touch each other directly:
 *
 * - **Observe and publish.** A socket opening or closing, or a client saying it is typing, becomes an
 *   event on `presence.update` / `typing.start`. The observing node publishes and does not deliver.
 * - **Consume and deliver.** [deliver] is called from the Kafka listener on *every* node, and each one
 *   fans out to the subscriptions and sessions it happens to hold locally.
 *
 * The hop is what makes the feature work at more than one node: Bob's phone connects to node A while
 * Alice watches him from node B, and only a broadcast group puts that transition in front of her.
 * Same shape as `call.signal`, for the same reason (`docs/KAFKA.md` §10) — the difference being that
 * here the gateway is its own producer, so with a single node an event is published and consumed by
 * the same process.
 *
 * Three properties still hold, and none of them changed with the move to Kafka:
 *
 * - **Nothing is durable.** The gateway consumes at `latest`, so a restart never replays a stale
 *   indicator, and a dropped event degrades to a missing dot that the next subscribe corrects or an
 *   indicator that expires client-side. There is no catch-up path and there must not be one.
 * - **No push notification, ever.** An offline user is not told somebody is typing at them.
 * - **The online *decision* is still node-local**, taken from this node's registry. Kafka fixes the
 *   fan-out leg, not this one — see [snapshotOf] and `docs/ARCHITECTURE.md` §6.
 */
@Component
class PresenceService(
    private val subscriptions: PresenceSubscriptions,
    private val lastSeen: LastSeenStore,
    private val registry: SessionRegistry,
    private val membership: DialogMembershipResolver,
    private val dispatcher: FrameDispatcher,
    private val producer: PresenceEventProducer
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    // ---- subscriptions: node-local, and deliberately not on a topic ----

    /**
     * Subscribes this session to the presence of everyone else in the dialog, and answers
     * immediately with a snapshot per peer.
     *
     * **The subscription and its snapshot both stay in this process.** A subscription is a fact about
     * one connection, which lives on exactly one node; publishing it would tell every other node
     * about a socket it cannot serve. The snapshot is addressed to that same single connection, so
     * routing it through the broker would be this node asking the broker to hand it back a frame for
     * a socket already in its hand.
     *
     * The snapshot is what makes the subscription usable at all: a client that only received
     * transitions would not know whether a peer was online until they next changed state, which for
     * somebody sitting connected all day is never.
     */
    fun subscribe(session: RelaySession, dialogId: String): PresenceSubscribeResult {
        val subjects = when (val membership = membership.resolve(dialogId, session.userId)) {
            is DialogMembershipResult.Rejected ->
                return PresenceSubscribeResult.Rejected(membership.code, membership.message)
            // Yourself excluded: your own presence is not news, and your other devices are not peers.
            is DialogMembershipResult.Found -> membership.participantIds.toSet() - session.userId
        }
        subscriptions.subscribe(session, dialogId, subjects)
        subjects.forEach { subject ->
            dispatcher.deliverToSessions(listOf(session), snapshotOf(subject))
        }
        logger.debug("Session {} subscribed to presence of {} in dialog {}", session.sessionId, subjects, dialogId)
        return PresenceSubscribeResult.Subscribed
    }

    /**
     * Silent, always — including for a dialog that was never subscribed. There is nothing a client
     * could do about a failed unsubscribe, and the subscription dies with the socket anyway.
     */
    fun unsubscribe(session: RelaySession, dialogId: String) {
        subscriptions.unsubscribe(session, dialogId)
    }

    /** Called when a socket closes, so a dead session stops being a subscriber. */
    fun forget(session: RelaySession) {
        subscriptions.forget(session)
    }

    // ---- observe and publish ----

    /**
     * Publishes a typing indicator for the other participants of the dialog.
     *
     * **The recipient list is resolved here, on the publishing node, and travels with the event.** A
     * consuming node must not have to ask message-service who is in a dialog it holds one socket of,
     * and a client must never be able to name its own recipients — that is the same rule the send
     * path follows for the same reason.
     *
     * **Silent on every failure, like `message.read` and unlike a send.** A client cannot act on it,
     * the next keystroke supersedes this frame, and answering the highest-volume frame in the protocol
     * with an error per emission would cost more than the feature.
     */
    fun typing(session: RelaySession, dialogId: String) {
        when (val membership = membership.resolve(dialogId, session.userId)) {
            is DialogMembershipResult.Rejected ->
                logger.debug(
                    "Dropping typing from session {} for dialog {}: {}",
                    session.sessionId, dialogId, membership.code
                )
            is DialogMembershipResult.Found -> {
                // The typist's own devices are excluded: "you are typing" on your tablet is a bug.
                val recipients = membership.participantIds.filter { it != session.userId }
                if (recipients.isEmpty()) return
                producer.publish(
                    TypingEvent(dialogId = dialogId, userId = session.userId, recipientIds = recipients)
                )
            }
        }
    }

    /**
     * Published on a user's **first** session, not on every connect: a second device does not change
     * whether they are online, and a subscriber redrawing on each of them would flicker.
     */
    fun announceOnline(userId: String) {
        producer.publish(PresenceEvent(userId, PresenceStatuses.ONLINE))
    }

    /**
     * Published on a user's **last** session closing, which is the moment they are actually away.
     *
     * [at] is stamped by this node rather than by whoever consumes the event: it is when the user
     * went away, and a consumer's clock reading would be off by however long the queue took.
     */
    fun announceOffline(userId: String, at: Instant) {
        producer.publish(PresenceEvent(userId, PresenceStatuses.OFFLINE, lastSeen = at))
    }

    // ---- consume and deliver ----

    /**
     * Fans a transition out to this node's subscribers, and records last-seen on the way through.
     *
     * **Last-seen is populated here rather than where the event was published**, so every node ends up
     * holding the same answer for a user that went offline on any of them — including a node that has
     * never held one of that user's sockets. The consequence is that a broker outage leaves last-seen
     * unrecorded, which is consistent: with no events flowing, presence is not being delivered either.
     */
    fun deliver(event: PresenceEvent) {
        val online = event.status == PresenceStatuses.ONLINE
        val seenAt = event.lastSeen
        if (!online && seenAt != null) lastSeen.record(event.userId, seenAt)

        val subscribers = subscriptions.subscribersOf(event.userId)
        if (subscribers.isEmpty()) return
        dispatcher.deliverToSessions(
            subscribers,
            OutboundFrame.PresenceUpdate(
                userId = event.userId,
                // Translated, not passed through. The event vocabulary and the wire vocabulary happen
                // to spell these the same, and mapping rather than forwarding is what keeps a change to
                // the internal one from silently becoming a change to the client contract — the same
                // reason the two Jackson mappers are separate. Anything unrecognised reads as offline,
                // which is what `docs/PROTOCOL.md` §4.2 tells clients to do with it too.
                status = if (online) PresenceStatus.ONLINE else PresenceStatus.OFFLINE,
                // An online user carries no last-seen: the status already says they are here.
                lastSeen = if (online) null else seenAt
            )
        )
        logger.debug("Delivered {} for user {} to {} session(s)", event.status, event.userId, subscribers.size)
    }

    /**
     * Fans an indicator out to whichever of its recipients this node holds. Recipients that are
     * connected elsewhere are served by the node that holds them, off the same event.
     */
    fun deliver(event: TypingEvent) {
        dispatcher.deliverToUsers(
            event.recipientIds,
            OutboundFrame.TypingStart(dialogId = event.dialogId, userId = event.userId)
        )
    }

    /**
     * The answer to "is this user online", taken from this node's own registry.
     *
     * **This is the one part of presence that Kafka does not fix.** A second node would answer for a
     * user it holds no session of, and say offline. Getting it right needs the shared session registry
     * (`docs/ARCHITECTURE.md` §6) — the same gap that makes the socket-XOR-push decision wrong at two
     * nodes, and the reason the gateway still runs as one.
     */
    private fun snapshotOf(userId: String): OutboundFrame.PresenceUpdate =
        if (registry.isOnline(userId)) {
            OutboundFrame.PresenceUpdate(userId, PresenceStatus.ONLINE, lastSeen = null)
        } else {
            OutboundFrame.PresenceUpdate(userId, PresenceStatus.OFFLINE, lastSeen = lastSeen.of(userId))
        }
}

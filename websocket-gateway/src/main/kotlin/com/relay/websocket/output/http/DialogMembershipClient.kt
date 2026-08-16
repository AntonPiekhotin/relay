package com.relay.websocket.output.http

/**
 * Outcome of asking message-service who is in a dialog. [Rejected] carries the code that goes into
 * the client's `error` frame, in the shape of [CallSignalResult] and for the same reason: the
 * transport's failures are already translated by the time the router sees them.
 */
sealed interface DialogMembershipResult {

    /** Everyone in the dialog, **including the caller** — the caller is a participant by definition
     *  here, since a non-participant is answered [Rejected]. */
    data class Found(val participantIds: List<String>) : DialogMembershipResult

    data class Rejected(val code: String, val message: String) : DialogMembershipResult
}

/**
 * Port for resolving dialog membership, which presence and typing both need: the wire addresses them
 * by dialog, the gateway relays them to people, and only message-service knows who those are.
 *
 * **Why HTTP and not Kafka**, when a send and a read both go to a topic: the gateway needs an
 * answer before it can do anything at all — a subscription with no participant list is not a
 * subscription, and a typing frame has nowhere to go. Same reasoning as [CallClient], and the same
 * mechanism (`lb://message` through Eureka).
 *
 * **Why not the client-facing endpoint with the user's own token**: the gateway does not keep the
 * handshake token, and a token that expires mid-socket would start failing presence for a
 * connection that is still perfectly authenticated. `/internal` with the identity taken from the
 * session is the pattern every call signal already uses.
 *
 * Implementations must not throw — a message-service that is down is a [DialogMembershipResult
 * .Rejected], not an exception escaping into a WebSocket handler.
 */
interface DialogMembershipClient {

    fun participants(dialogId: String, callerId: String): DialogMembershipResult
}

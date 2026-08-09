package com.relay.common.event

/**
 * The contents of [CallSignalEvent.signal].
 *
 * The gateway relays that object verbatim, so this vocabulary is a contract in two directions at
 * once: between call-service and the gateway (which reads the verb to decide whether an unreachable
 * callee needs a push), and between call-service and the client (which reads all of it). It lives
 * in `common` for the first reason — both backend sides must agree on the spelling of `invite` or
 * the push half silently stops happening.
 *
 * Keys are snake_case. The gateway's wire mapper applies its naming strategy to object properties,
 * not to map keys, so a camelCase key here would reach the client camelCase and break the payload
 * convention in `docs/PROTOCOL.md` §3.
 */
object CallSignalVerbs {

    /** The caller's offer, ringing the callee. */
    const val INVITE = "invite"

    /** The callee's answer, going back to the caller. */
    const val ACCEPT = "accept"

    /** The callee declined. */
    const val REJECT = "reject"

    /** A trickle ICE candidate, relayed verbatim in either direction. */
    const val ICE = "ice"

    /** Either party ended the call. */
    const val HANGUP = "hangup"

    /**
     * Stop showing this call — sent to a user's *other* devices once one of them settled it, so a
     * phone stops ringing after the call was answered on a tablet.
     */
    const val CANCEL = "cancel"

    /** Rang out unanswered. Server-decided, sent to both parties. */
    const val MISSED = "missed"

    /** Progress a client could not have inferred; currently only "your invite is ringing". */
    const val STATE = "state"
}

object CallSignalKeys {

    const val VERB = "verb"
    const val MEDIA = "media"
    const val SDP = "sdp"
    const val CANDIDATE = "candidate"
    const val REASON = "reason"
    const val STATUS = "status"
    const val DIALOG_ID = "dialog_id"
    const val STARTED_AT = "started_at"
    const val DURATION_S = "duration_s"

    /**
     * When the server stops ringing and calls it missed. Carried on an invite so a client can show
     * an honest countdown, and so the gateway can stamp the push it raises for an offline callee —
     * a call push that arrives after this instant must not raise a call UI.
     */
    const val RING_EXPIRES_AT = "ring_expires_at"
}

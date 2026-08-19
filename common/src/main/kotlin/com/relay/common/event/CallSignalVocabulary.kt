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

    /**
     * You are invited to a group call. Distinct from [INVITE] on purpose: there is no SDP — the
     * client joins over REST and connects to the SFU with the token it gets back — and a 1:1-only
     * client ignores the unknown verb instead of building a broken peer connection.
     */
    const val GROUP_INVITE = "group_invite"

    /** A roster delta: someone joined the group call. */
    const val PARTICIPANT_JOINED = "participant_joined"

    /** A roster delta: someone left the group call (it continues for the rest). */
    const val PARTICIPANT_LEFT = "participant_left"

    /** A roster delta: an invitee declined. */
    const val PARTICIPANT_DECLINED = "participant_declined"

    /** A roster delta: an invitee rang out. Server-decided, like [MISSED]. */
    const val PARTICIPANT_MISSED = "participant_missed"

    /** The group call is over for everyone, with a reason. */
    const val GROUP_ENDED = "group_ended"
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

    /** `direct` or `group`. The gateway reads it to label the push it raises for a group_invite. */
    const val KIND = "kind"

    /** The subject of a roster delta — who joined, left, declined, or rang out. */
    const val USER_ID = "user_id"

    /** The roster on a group_invite: a list of `{"user_id": …, "state": …}` objects. */
    const val PARTICIPANTS = "participants"

    /** A participant's state inside a [PARTICIPANTS] entry. */
    const val STATE = "state"
}

package com.relay.call.service

import com.relay.call.model.Call
import com.relay.common.event.CallSignalKeys
import com.relay.common.event.CallSignalVerbs
import java.time.Instant

/**
 * Builds the opaque `signal` object the gateway relays inside a `call.signal` frame.
 *
 * The verb lives inside the signal rather than in the frame `type` deliberately: one outbound frame
 * type means the client-facing catalogue does not grow a type per WebRTC verb, and a client that
 * does not recognise a new verb ignores one signal instead of failing to route a frame.
 *
 * The vocabulary itself is in `common` — see `CallSignalVerbs` — because the gateway reads the verb
 * too.
 */
object CallSignals {

    fun invite(call: Call, sdp: String, ringExpiresAt: Instant): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.INVITE,
        CallSignalKeys.MEDIA to call.media.wireValue,
        CallSignalKeys.SDP to sdp,
        CallSignalKeys.DIALOG_ID to call.dialogId?.toString(),
        CallSignalKeys.STARTED_AT to call.startedAt.toString(),
        CallSignalKeys.RING_EXPIRES_AT to ringExpiresAt.toString()
    )

    fun accept(sdp: String): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.ACCEPT,
        CallSignalKeys.SDP to sdp
    )

    fun reject(reason: String?): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.REJECT,
        CallSignalKeys.REASON to reason
    )

    fun ice(candidate: Map<String, Any?>): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.ICE,
        CallSignalKeys.CANDIDATE to candidate
    )

    fun hangup(call: Call, reason: String?): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.HANGUP,
        CallSignalKeys.REASON to reason,
        CallSignalKeys.DURATION_S to call.durationSeconds
    )

    fun cancel(reason: String): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.CANCEL,
        CallSignalKeys.REASON to reason
    )

    fun missed(call: Call): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.MISSED,
        CallSignalKeys.REASON to call.endReason
    )

    fun state(call: Call): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.STATE,
        CallSignalKeys.STATUS to call.status.wireValue
    )

    /** Reasons that land in `calls.end_reason` and travel with cancel/hangup/missed signals. */
    object Reasons {
        const val HANGUP = "hangup"
        const val CALLER_CANCELED = "caller_canceled"
        const val CALLEE_CANCELED = "callee_canceled"
        const val DECLINED = "declined"
        const val RING_TIMEOUT = "ring_timeout"
        const val ANSWERED_ELSEWHERE = "answered_elsewhere"
        const val SETTLED_ELSEWHERE = "settled_elsewhere"

        /** Group calls only: every invitee declined before anybody joined. */
        const val ALL_DECLINED = "all_declined"

        /** Group calls only: the last joined participant left. */
        const val ALL_LEFT = "all_left"

        /** Group calls only: this user joined on another of their devices — stop ringing. */
        const val JOINED_ELSEWHERE = "joined_elsewhere"

        const val DISCONNECTED = "disconnected"
    }
}

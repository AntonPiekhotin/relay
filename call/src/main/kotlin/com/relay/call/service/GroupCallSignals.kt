package com.relay.call.service

import com.relay.call.model.Call
import com.relay.call.model.CallParticipant
import com.relay.common.event.CallSignalKeys
import com.relay.common.event.CallSignalVerbs
import java.time.Instant

/**
 * The group-call half of the signal vocabulary, built beside [CallSignals] rather than into it.
 * Same envelope, same opaque `call.signal` frame — a 1:1-only client ignores every verb here and
 * loses nothing, which is what lets group calls ship without touching the frame catalogue.
 */
object GroupCallSignals {

    /**
     * Rings an invitee. No SDP, unlike the 1:1 invite — the client joins over REST and connects to
     * the SFU with the token it gets back. Carries `media` and `ring_expires_at` in the same keys
     * as the 1:1 invite, because the gateway's push decision reads exactly those.
     */
    fun groupInvite(call: Call, participants: List<CallParticipant>, ringExpiresAt: Instant): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.GROUP_INVITE,
        CallSignalKeys.KIND to call.kind.wireValue,
        CallSignalKeys.MEDIA to call.media.wireValue,
        CallSignalKeys.STARTED_AT to call.startedAt.toString(),
        CallSignalKeys.RING_EXPIRES_AT to ringExpiresAt.toString(),
        CallSignalKeys.PARTICIPANTS to roster(participants)
    )

    fun participantJoined(userId: String): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.PARTICIPANT_JOINED,
        CallSignalKeys.USER_ID to userId
    )

    fun participantLeft(userId: String, reason: String?): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.PARTICIPANT_LEFT,
        CallSignalKeys.USER_ID to userId,
        CallSignalKeys.REASON to reason
    )

    fun participantDeclined(userId: String, reason: String?): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.PARTICIPANT_DECLINED,
        CallSignalKeys.USER_ID to userId,
        CallSignalKeys.REASON to reason
    )

    /**
     * An invitee rang out on a call that goes on without them. Sent to *every* participant
     * including the rung-out invitee — their devices stop ringing on it, everyone else updates the
     * roster — so one signal serves both audiences.
     */
    fun participantMissed(userId: String): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.PARTICIPANT_MISSED,
        CallSignalKeys.USER_ID to userId
    )

    /** The call is over for everyone. The one group verb every client must handle. */
    fun groupEnded(call: Call): Map<String, Any?> = mapOf(
        CallSignalKeys.VERB to CallSignalVerbs.GROUP_ENDED,
        CallSignalKeys.REASON to call.endReason,
        CallSignalKeys.DURATION_S to call.durationSeconds
    )

    private fun roster(participants: List<CallParticipant>): List<Map<String, Any?>> =
        participants.map {
            mapOf(
                CallSignalKeys.USER_ID to it.userId,
                CallSignalKeys.STATE to it.state.wireValue
            )
        }
}

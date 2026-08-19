package com.relay.call.service

import com.relay.call.config.CallProperties
import com.relay.call.model.Call
import com.relay.call.model.CallKind
import com.relay.call.model.CallMedia
import com.relay.call.model.CallParticipant
import com.relay.call.model.CallStatus
import com.relay.call.model.ParticipantState
import com.relay.call.model.dto.CreateGroupCallRequest
import com.relay.call.model.dto.CreateGroupCallResult
import com.relay.call.model.dto.GroupCallResponse
import com.relay.call.model.dto.event.CallNotificationRequested
import com.relay.call.model.dto.event.CallSignalRaised
import com.relay.call.model.dto.event.GroupCallTerminated
import com.relay.call.repository.ActiveCallRepository
import com.relay.call.repository.CallParticipantRepository
import com.relay.call.repository.CallRepository
import com.relay.call.service.sfu.RoomTokenFactory
import com.relay.call.util.mapper.toGroupResponse
import com.relay.call.util.mapper.toSfuAccess
import com.relay.common.event.CallSignalEvent
import com.relay.common.event.NotificationRequestedEvent
import com.relay.common.exception.RelayException
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The group-call state machine. REST-driven, unlike [CallService]'s frames, because every entry
 * point has to hand back an SFU room token synchronously — and there is no SDP to relay, so the
 * socket buys nothing on the way in. Outbound, everything still rides the `call.signal` frame.
 *
 * Concurrency is settled by two database facts and nothing else:
 *
 * - **Cross-call busy is the `active_calls` primary key.** The initiator claims a row at create;
 *   every invitee claims one only at join — a ringing invitee is not busy, and one busy invitee
 *   cannot fail the whole call. The claim is `ON CONFLICT DO NOTHING` so join can tell "claimed by
 *   this call" (idempotent) from "claimed by another" (USER_BUSY) without a doomed transaction.
 * - **Every per-call transition runs under `SELECT ... FOR UPDATE` on the call row.** Two
 *   participants leaving at once must not each see the other still present and both conclude they
 *   are not the last one out — see [CallRepository.findWithLockById]. The optimistic version stays
 *   for the direct paths and is not relied on here.
 *
 * Join and leave truth: a client's own REST calls are the fast path, the SFU's webhooks are the
 * authority for a client that vanished, and [reconcile] is the backstop when even the webhook was
 * lost.
 */
@Service
class GroupCallService(
    private val callRepository: CallRepository,
    private val participantRepository: CallParticipantRepository,
    private val activeCallRepository: ActiveCallRepository,
    private val roomTokenFactory: RoomTokenFactory,
    private val events: ApplicationEventPublisher,
    private val properties: CallProperties
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Creates the call and rings every invitee. The initiator is joined from the first moment —
     * they are on the call they just started — and is the only one claimed as busy.
     */
    @Transactional
    fun create(callerId: String, request: CreateGroupCallRequest): CreateGroupCallResult {
        val callId = request.callId.toUuid()
        val inviteeIds = request.inviteeIds.distinct()
        if (callerId in inviteeIds) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "A user cannot invite themselves")
        }
        if (inviteeIds.size + 1 > properties.group.maxParticipants) {
            throw RelayException(
                HttpStatus.BAD_REQUEST.value(),
                "A group call holds at most ${properties.group.maxParticipants} participants"
            )
        }

        callRepository.findById(callId).orElse(null)?.let { existing ->
            return CreateGroupCallResult(created = false, response = describeRetriedCreate(existing, callerId))
        }

        val call = callRepository.saveAndFlush(
            Call(
                id = callId,
                initiator = callerId,
                media = CallMedia.ofWire(request.media),
                kind = CallKind.GROUP
            )
        )
        val participants = participantRepository.saveAllAndFlush(
            listOf(
                CallParticipant(
                    callId = callId,
                    userId = callerId,
                    joinedAt = call.startedAt,
                    state = ParticipantState.JOINED
                )
            ) + inviteeIds.map { CallParticipant(callId = callId, userId = it) }
        )
        if (activeCallRepository.claim(callerId, callId) == 0) {
            throw RelayException(HttpStatus.CONFLICT.value(), "$callerId is already in a call")
        }

        raise(call, callerId, GroupCallSignals.groupInvite(call, participants, ringExpiryOf(call)), inviteeIds)
        raise(call, callerId, CallSignals.state(call), listOf(callerId))

        logger.debug("Group call {} ringing: {} -> {} invitee(s)", callId, callerId, inviteeIds.size)
        return CreateGroupCallResult(
            created = true,
            response = call.toGroupResponse(participants, ringExpiryOf(call), token(call, callerId))
        )
    }

    /**
     * Enters the call — and doubles as the token refresh for someone already in it. Legal from
     * `invited`, `declined` (changed their mind while it still lives), and `left` (rejoining an
     * ongoing call); the busy claim is what refuses someone who is on another call entirely.
     */
    @Transactional
    fun join(callerId: String, rawCallId: String, sessionId: String?): GroupCallResponse {
        val call = requireGroupCallLocked(rawCallId)
        val participants = participantRepository.findAllByCallId(call.id)
        val me = requireParticipant(call, participants, callerId)
        requireLive(call)

        if (me.state == ParticipantState.JOINED) {
            // Idempotent: a retry, or a reconnecting client refreshing its token.
            return call.toGroupResponse(participants, ringExpiryOf(call), token(call, callerId))
        }

        if (activeCallRepository.claim(callerId, call.id) == 0) {
            val holder = activeCallRepository.findById(callerId).orElse(null)
            if (holder == null || holder.callId != call.id) {
                throw RelayException(HttpStatus.CONFLICT.value(), "$callerId is already in a call")
            }
            // Claimed by this very call while the participant row says otherwise — a previous
            // transition half-landed. The claim is ours; proceed and heal the state.
        }

        val now = Instant.now()
        me.state = ParticipantState.JOINED
        me.joinedAt = now
        me.leftAt = null
        if (call.status == CallStatus.RINGING && callerId != call.initiator) {
            call.status = CallStatus.ANSWERED
            call.answeredAt = now
            callRepository.saveAndFlush(call)
        }
        participantRepository.saveAndFlush(me)

        raise(call, callerId, GroupCallSignals.participantJoined(callerId), others(participants, callerId))
        stopOwnOtherDevices(call, callerId, sessionId, CallSignals.Reasons.JOINED_ELSEWHERE)

        logger.debug("{} joined group call {}", callerId, call.id)
        return call.toGroupResponse(participants, ringExpiryOf(call), token(call, callerId))
    }

    /** Refuses while ringing. Only an `invited` participant can decline; joined people leave. */
    @Transactional
    fun decline(callerId: String, rawCallId: String, reason: String?, sessionId: String?): GroupCallResponse {
        val call = requireGroupCallLocked(rawCallId)
        val participants = participantRepository.findAllByCallId(call.id)
        val me = requireParticipant(call, participants, callerId)
        requireLive(call)
        if (me.state != ParticipantState.INVITED) {
            throw RelayException(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "$callerId is ${me.state.wireValue}, not invited — only a ringing invitee can decline"
            )
        }

        me.state = ParticipantState.DECLINED
        participantRepository.saveAndFlush(me)

        raise(
            call,
            callerId,
            GroupCallSignals.participantDeclined(callerId, reason ?: CallSignals.Reasons.DECLINED),
            others(participants, callerId)
        )
        stopOwnOtherDevices(call, callerId, sessionId, CallSignals.Reasons.SETTLED_ELSEWHERE)

        // The last refusal ends a call nobody ever joined. Under the row lock the count is the
        // truth, not a stale read.
        if (call.status == CallStatus.RINGING && participants.none { it.state == ParticipantState.INVITED }) {
            terminateGroup(call, participants, callerId, CallStatus.REJECTED, CallSignals.Reasons.ALL_DECLINED)
        }

        logger.debug("{} declined group call {}", callerId, call.id)
        return call.toGroupResponse(participants, ringExpiryOf(call), livekit = null)
    }

    /**
     * Steps out. Idempotent — leaving a call one is not in, or that already ended, is a no-op, so
     * the client's leave and the SFU's `participant_left` webhook can both land without either
     * being an error.
     */
    @Transactional
    fun leave(callerId: String, rawCallId: String, sessionId: String?): GroupCallResponse {
        val call = requireGroupCallLocked(rawCallId)
        val participants = participantRepository.findAllByCallId(call.id)
        val me = requireParticipant(call, participants, callerId)
        if (call.status.isTerminal || me.state != ParticipantState.JOINED) {
            return call.toGroupResponse(participants, ringExpiryOf(call), livekit = null)
        }
        leaveJoined(call, participants, me, reason = null)
        logger.debug("{} left group call {}", callerId, call.id)
        return call.toGroupResponse(participants, ringExpiryOf(call), livekit = null)
    }

    /** The call as this participant may see it. Mints nothing — join is what admits to the room. */
    @Transactional(readOnly = true)
    fun describe(callerId: String, rawCallId: String): GroupCallResponse {
        val call = requireGroupCall(rawCallId)
        val participants = participantRepository.findAllByCallId(call.id)
        requireParticipant(call, participants, callerId)
        return call.toGroupResponse(participants, ringExpiryOf(call), livekit = null)
    }

    /** ── the SFU's account of reality: webhooks and reconciliation ─────────────────────────── */

    /**
     * The SFU watched this participant disconnect — the app was killed, the network went away.
     * Same transition as a REST leave; being told twice is a no-op by the same guards.
     */
    @Transactional
    fun onSfuParticipantLeft(callId: UUID, identity: String) {
        val call = callRepository.findWithLockById(callId) ?: return
        if (call.kind != CallKind.GROUP || call.status.isTerminal) return
        val participants = participantRepository.findAllByCallId(callId)
        val me = participants.firstOrNull { it.userId == identity } ?: return
        if (me.state != ParticipantState.JOINED) return
        leaveJoined(call, participants, me, CallSignals.Reasons.DISCONNECTED)
        logger.debug("SFU reported {} gone from group call {}", identity, callId)
    }

    /** The SFU closed the room. Whatever the database still believes, the call is over. */
    @Transactional
    fun onSfuRoomFinished(callId: UUID) {
        val call = callRepository.findWithLockById(callId) ?: return
        if (call.kind != CallKind.GROUP || call.status.isTerminal) return
        val participants = participantRepository.findAllByCallId(callId)
        terminateGroup(call, participants, call.initiator, CallStatus.ENDED, CallSignals.Reasons.ALL_LEFT)
        logger.debug("SFU finished the room; group call {} ended", callId)
    }

    /**
     * Removes joined participants the SFU does not know, on one call, against a room roster the
     * caller fetched *outside* this transaction. The grace period spares people who claimed over
     * REST a moment ago and are still connecting.
     */
    @Transactional
    fun reconcile(callId: UUID, identitiesInRoom: Set<String>, now: Instant = Instant.now()): Int {
        val call = callRepository.findWithLockById(callId) ?: return 0
        if (call.kind != CallKind.GROUP || call.status.isTerminal) return 0
        val participants = participantRepository.findAllByCallId(callId)
        val graceCutoff = now.minus(properties.reconcileGrace)
        val vanished = participants.filter {
            it.state == ParticipantState.JOINED &&
                it.userId !in identitiesInRoom &&
                (it.joinedAt ?: now).isBefore(graceCutoff)
        }
        vanished.forEach { me ->
            if (!call.status.isTerminal) {
                leaveJoined(call, participants, me, CallSignals.Reasons.DISCONNECTED)
            }
        }
        if (vanished.isNotEmpty()) {
            logger.info("Reconciled group call {}: removed {} vanished participant(s)", callId, vanished.size)
        }
        return vanished.size
    }

    /** Answered group calls worth reconciling. Ringing ones belong to the ring sweep. */
    @Transactional(readOnly = true)
    fun liveGroupCallIds(): List<UUID> =
        callRepository.findAllByKindAndStatusIn(CallKind.GROUP, listOf(CallStatus.ANSWERED)).map { it.id }

    /** ── the ring sweeps ────────────────────────────────────────────────────────────────────── */

    /** Group calls still ringing past the timeout — nobody ever joined. */
    @Transactional(readOnly = true)
    fun findRungOutGroupCallIds(now: Instant = Instant.now()): List<UUID> =
        callRepository
            .findAllByKindAndStatusAndStartedAtBefore(
                CallKind.GROUP, CallStatus.RINGING, now.minus(properties.ringTimeout)
            )
            .map { it.id }

    /** Gives up on a group call nobody joined: missed for every invitee, with a push each. */
    @Transactional
    fun expireRungOutGroupCall(callId: UUID): Boolean {
        val call = callRepository.findWithLockById(callId) ?: return false
        if (call.status != CallStatus.RINGING) return false
        val participants = participantRepository.findAllByCallId(callId)
        val rungOut = participants.filter { it.state == ParticipantState.INVITED }

        terminateGroup(call, participants, call.initiator, CallStatus.MISSED, CallSignals.Reasons.RING_TIMEOUT)
        rungOut.forEach { requestMissedCallPush(call, it.userId) }

        logger.info("Group call {} rang out with nobody joining", callId)
        return true
    }

    /** Answered group calls on which somebody is still ringing past the timeout. */
    @Transactional(readOnly = true)
    fun findGroupCallIdsWithPendingInvites(now: Instant = Instant.now()): List<UUID> =
        callRepository.findAnsweredGroupCallIdsWithPendingInvites(now.minus(properties.ringTimeout))

    /**
     * Rings out the individual invitees of a call that goes on without them. The call itself stays
     * answered; each rung-out invitee gets a missed-call push, and everyone — the invitee's own
     * ringing devices included — gets the roster delta.
     */
    @Transactional
    fun expirePendingInvites(callId: UUID): Int {
        val call = callRepository.findWithLockById(callId) ?: return 0
        if (call.kind != CallKind.GROUP || call.status != CallStatus.ANSWERED) return 0
        val participants = participantRepository.findAllByCallId(callId)
        val pending = participants.filter { it.state == ParticipantState.INVITED }
        if (pending.isEmpty()) return 0

        pending.forEach { it.state = ParticipantState.MISSED }
        participantRepository.saveAllAndFlush(pending)
        val everyone = participants.map { it.userId }
        pending.forEach { invitee ->
            raise(call, call.initiator, GroupCallSignals.participantMissed(invitee.userId), everyone)
            requestMissedCallPush(call, invitee.userId)
        }
        logger.info("Group call {}: {} invitee(s) rang out", callId, pending.size)
        return pending.size
    }

    /** ── internals ──────────────────────────────────────────────────────────────────────────── */

    /**
     * A retried create. Same contract as the direct call's: the same id is the same call, re-rung
     * while it still rings, described once settled. The token comes back only while the retrier is
     * actually joined — an initiator who has since left goes through [join] like anybody else.
     */
    private fun describeRetriedCreate(existing: Call, callerId: String): GroupCallResponse {
        requireGroup(existing)
        if (existing.initiator != callerId) {
            throw RelayException(
                HttpStatus.CONFLICT.value(),
                "Call ${existing.id} already exists and belongs to someone else"
            )
        }
        val participants = participantRepository.findAllByCallId(existing.id)
        if (existing.status == CallStatus.RINGING) {
            val stillRinging = participants.filter { it.state == ParticipantState.INVITED }.map { it.userId }
            if (stillRinging.isNotEmpty()) {
                logger.debug("Re-publishing the group invite for retried call {}", existing.id)
                raise(
                    existing,
                    callerId,
                    GroupCallSignals.groupInvite(existing, participants, ringExpiryOf(existing)),
                    stillRinging
                )
            }
        }
        val mine = participants.firstOrNull { it.userId == callerId }
        val livekit = if (!existing.status.isTerminal && mine?.state == ParticipantState.JOINED) {
            token(existing, callerId)
        } else {
            null
        }
        return existing.toGroupResponse(participants, ringExpiryOf(existing), livekit)
    }

    /**
     * A joined participant leaves — by REST, by webhook, or by reconciliation. Decides under the
     * row lock whether the call goes on: the initiator abandoning a ring cancels it, the last one
     * out ends it, and anything else is a roster delta.
     */
    private fun leaveJoined(call: Call, participants: List<CallParticipant>, me: CallParticipant, reason: String?) {
        me.state = ParticipantState.LEFT
        me.leftAt = Instant.now()
        participantRepository.saveAndFlush(me)
        activeCallRepository.deleteByUserIdAndCallId(me.userId, call.id)

        when {
            call.status == CallStatus.RINGING && me.userId == call.initiator ->
                terminateGroup(call, participants, me.userId, CallStatus.ENDED, CallSignals.Reasons.CALLER_CANCELED)

            participants.none { it.state == ParticipantState.JOINED } ->
                terminateGroup(call, participants, me.userId, CallStatus.ENDED, CallSignals.Reasons.ALL_LEFT)

            else ->
                raise(call, me.userId, GroupCallSignals.participantLeft(me.userId, reason), others(participants, me.userId))
        }
    }

    /**
     * The one way a group call ends. Frees every busy row, stamps the roster, tells everyone with
     * one `group_ended`, and asks for the SFU room to be closed after commit.
     */
    private fun terminateGroup(
        call: Call,
        participants: List<CallParticipant>,
        actorId: String,
        status: CallStatus,
        reason: String
    ) {
        call.terminate(status, reason)
        participants.forEach {
            when {
                it.state == ParticipantState.JOINED -> {
                    it.state = ParticipantState.LEFT
                    it.leftAt = call.endedAt
                }
                it.state == ParticipantState.INVITED && status == CallStatus.MISSED ->
                    it.state = ParticipantState.MISSED
            }
        }
        callRepository.saveAndFlush(call)
        participantRepository.saveAllAndFlush(participants)
        activeCallRepository.deleteAllByCallId(call.id)

        raise(call, actorId, GroupCallSignals.groupEnded(call), participants.map { it.userId })
        events.publishEvent(GroupCallTerminated(call.id))
    }

    private fun requestMissedCallPush(call: Call, recipientId: String) {
        events.publishEvent(
            CallNotificationRequested(
                NotificationRequestedEvent.missedCall(
                    recipientId = recipientId,
                    callId = call.id.toString(),
                    callerId = call.initiator,
                    media = call.media.wireValue,
                    requestedAt = call.endedAt ?: Instant.now(),
                    callKind = NotificationRequestedEvent.CALL_KIND_GROUP
                )
            )
        )
    }

    private fun raise(call: Call, fromUserId: String, signal: Map<String, Any?>, recipientIds: List<String>) {
        if (recipientIds.isEmpty()) return
        events.publishEvent(
            CallSignalRaised(
                CallSignalEvent(
                    callId = call.id.toString(),
                    fromUserId = fromUserId,
                    signal = signal,
                    recipientIds = recipientIds
                )
            )
        )
    }

    /**
     * Tells the actor's *other* devices to stop ringing. Without a session id the acting device is
     * told too — harmless, it is showing an in-call UI, not a ringing one.
     */
    private fun stopOwnOtherDevices(call: Call, userId: String, sessionId: String?, reason: String) {
        events.publishEvent(
            CallSignalRaised(
                CallSignalEvent(
                    callId = call.id.toString(),
                    fromUserId = userId,
                    signal = CallSignals.cancel(reason),
                    recipientIds = listOf(userId),
                    excludeSessionIds = listOfNotNull(sessionId)
                )
            )
        )
    }

    private fun token(call: Call, userId: String) =
        roomTokenFactory.joinToken(call.id.toString(), userId).toSfuAccess()

    private fun requireGroupCallLocked(rawCallId: String): Call {
        val call = callRepository.findWithLockById(rawCallId.toUuid())
            ?: throw RelayException(HttpStatus.NOT_FOUND.value(), "Call $rawCallId not found")
        requireGroup(call)
        return call
    }

    private fun requireGroupCall(rawCallId: String): Call {
        val call = callRepository.findById(rawCallId.toUuid()).orElseThrow {
            RelayException(HttpStatus.NOT_FOUND.value(), "Call $rawCallId not found")
        }
        requireGroup(call)
        return call
    }

    private fun requireGroup(call: Call) {
        if (call.kind != CallKind.GROUP) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "Call ${call.id} is not a group call")
        }
    }

    private fun requireParticipant(call: Call, participants: List<CallParticipant>, userId: String): CallParticipant =
        participants.firstOrNull { it.userId == userId }
            ?: throw RelayException(HttpStatus.FORBIDDEN.value(), "$userId is not a participant of call ${call.id}")

    private fun requireLive(call: Call) {
        if (call.status.isTerminal) {
            throw RelayException(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Call ${call.id} is ${call.status.wireValue}"
            )
        }
    }

    private fun others(participants: List<CallParticipant>, userId: String): List<String> =
        participants.map { it.userId }.filter { it != userId }

    private fun ringExpiryOf(call: Call): Instant = call.startedAt.plus(properties.ringTimeout)

    private fun String.toUuid(): UUID = try {
        UUID.fromString(this)
    } catch (ex: IllegalArgumentException) {
        throw RelayException(HttpStatus.BAD_REQUEST.value(), "'$this' is not a valid call id", ex)
    }
}

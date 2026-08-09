package com.relay.call.service

import com.relay.call.config.CallProperties
import com.relay.call.model.ActiveCall
import com.relay.call.model.Call
import com.relay.call.model.CallMedia
import com.relay.call.model.CallParticipant
import com.relay.call.model.CallStatus
import com.relay.call.model.dto.CallHistoryResponse
import com.relay.call.model.dto.CallResponse
import com.relay.call.model.dto.event.CallNotificationRequested
import com.relay.call.model.dto.event.CallSignalRaised
import com.relay.call.repository.ActiveCallRepository
import com.relay.call.repository.CallParticipantRepository
import com.relay.call.repository.CallRepository
import com.relay.call.util.mapper.toHistoryEntry
import com.relay.call.util.mapper.toResponse
import com.relay.common.dto.AcceptCallRequest
import com.relay.common.dto.HangupCallRequest
import com.relay.common.dto.IceCandidateRequest
import com.relay.common.dto.InviteCallRequest
import com.relay.common.dto.RejectCallRequest
import com.relay.common.event.CallSignalEvent
import com.relay.common.event.NotificationRequestedEvent
import com.relay.common.exception.RelayException
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The call state machine. Every signal a client sends lands here, and every signal a client
 * receives is raised here.
 *
 * Two things this service deliberately does not do: touch media (it never sees an RTP packet, and
 * SDP and ICE payloads are relayed as opaque blobs), and trust a client's account of what happened
 * (the ring timeout, the busy check, and every state guard are decided from the database).
 *
 * Direct calls only. The schema supports more participants, but [invite] takes exactly one callee
 * and every guard below assumes two — group calls need a `join` verb and an SFU, not a wider loop.
 */
@Service
class CallService(
    private val callRepository: CallRepository,
    private val participantRepository: CallParticipantRepository,
    private val activeCallRepository: ActiveCallRepository,
    private val iceBuffer: IceCandidateBuffer,
    private val events: ApplicationEventPublisher,
    private val properties: CallProperties
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Starts ringing. The call id comes from the client, so a repeated invite is a retry of the
     * same call rather than a second one — and while that call is still ringing the invite is
     * re-published, because the likeliest reason a client retried is that the callee never got it.
     */
    @Transactional
    fun invite(request: InviteCallRequest): CallResponse {
        if (request.callerId == request.calleeId) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "A user cannot call themselves")
        }
        val callId = request.callId.toUuid()

        callRepository.findById(callId).orElse(null)?.let { existing ->
            return resendOrDescribe(existing, request)
        }
        val call = callRepository.saveAndFlush(
            Call(
                id = callId,
                initiator = request.callerId,
                media = CallMedia.ofWire(request.media),
                dialogId = request.dialogId?.toUuid()
            )
        )
        val participants = participantRepository.saveAllAndFlush(
            listOf(
                CallParticipant(callId = callId, userId = request.callerId, joinedAt = call.startedAt),
                CallParticipant(callId = callId, userId = request.calleeId)
            )
        )
        claimBoth(callId, request.callerId, request.calleeId)
        sendCallSignalToCallee(request, call)
        setCallerDevicesBusy(request, call)
        relayBuffered(callId, participants.map { it.userId })

        logger.debug("Call {} ringing: {} -> {}", callId, request.callerId, request.calleeId)
        return call.toResponse(participants)
    }

    /**
     * The callee answers. The version check on [Call] is what settles two of their devices
     * answering at once — the loser fails to commit rather than overwriting the answer already on
     * its way to the caller.
     */
    @Transactional
    fun accept(rawCallId: String, request: AcceptCallRequest): CallResponse {
        val call = requireCall(rawCallId)
        val participants = participantRepository.findAllByCallId(call.id)
        requireParticipant(call, participants, request.userId)
        if (request.userId == call.initiator) {
            throw RelayException(HttpStatus.FORBIDDEN.value(), "The caller cannot answer their own call")
        }
        requireRinging(call)

        val answeredAt = Instant.now()
        call.status = CallStatus.ANSWERED
        call.answeredAt = answeredAt
        participants.firstOrNull { it.userId == request.userId }?.joinedAt = answeredAt
        callRepository.saveAndFlush(call)

        raise(call, request.userId, CallSignals.accept(request.sdp), listOf(call.initiator))
        stopOtherDevices(call, request.userId, request.sessionId, CallSignals.Reasons.ANSWERED_ELSEWHERE)

        logger.debug("Call {} answered by {}", call.id, request.userId)
        return call.toResponse(participants)
    }

    /** The callee declines. Distinct from a hangup: the call was never answered and was refused. */
    @Transactional
    fun reject(rawCallId: String, request: RejectCallRequest): CallResponse {
        val call = requireCall(rawCallId)
        val participants = participantRepository.findAllByCallId(call.id)
        requireParticipant(call, participants, request.userId)
        if (request.userId == call.initiator) {
            throw RelayException(
                HttpStatus.FORBIDDEN.value(),
                "The caller cannot reject their own call — hang up instead"
            )
        }
        requireRinging(call)

        val reason = request.reason ?: CallSignals.Reasons.DECLINED
        terminate(call, participants, CallStatus.REJECTED, reason)

        raise(call, request.userId, CallSignals.reject(reason), listOf(call.initiator))
        stopOtherDevices(call, request.userId, request.sessionId, CallSignals.Reasons.SETTLED_ELSEWHERE)

        logger.debug("Call {} rejected by {}", call.id, request.userId)
        return call.toResponse(participants)
    }

    /** Either party ends the call, answered or not. */
    @Transactional
    fun hangup(rawCallId: String, request: HangupCallRequest): CallResponse {
        val call = requireCall(rawCallId)
        val participants = participantRepository.findAllByCallId(call.id)
        requireParticipant(call, participants, request.userId)
        if (call.status.isTerminal) {
            logger.debug("Call {} is already {}, ignoring hangup", call.id, call.status.wireValue)
            return call.toResponse(participants)
        }

        val reason = request.reason ?: when {
            call.status == CallStatus.ANSWERED -> CallSignals.Reasons.HANGUP
            request.userId == call.initiator && call.status == CallStatus.RINGING -> CallSignals.Reasons.CALLER_CANCELED
            else -> CallSignals.Reasons.CALLEE_CANCELED
        }
        terminate(call, participants, CallStatus.ENDED, reason)

        raise(call, request.userId, CallSignals.hangup(call, reason), others(participants, request.userId))
        stopOtherDevices(call, request.userId, request.sessionId, CallSignals.Reasons.SETTLED_ELSEWHERE)

        logger.debug("Call {} ended by {} after {}s", call.id, request.userId, call.durationSeconds)
        return call.toResponse(participants)
    }

    /**
     * Relays one trickle ICE candidate to the other participant, unexamined.
     *
     * A candidate for a call that does not exist yet is buffered rather than dropped: it can
     * genuinely outrun its own offer, and dropping it breaks setup. A candidate for a call that has
     * *ended* is dropped — there is no peer left to give it to.
     */
    @Transactional(readOnly = true)
    fun relayIce(rawCallId: String, request: IceCandidateRequest) {
        val callId = rawCallId.toUuid()
        val call = callRepository.findById(callId).orElse(null)
        if (call == null) {
            iceBuffer.buffer(callId, request.userId, request.candidate)
            logger.debug("Buffered a candidate from {} for unknown call {}", request.userId, callId)
            return
        }
        if (call.status.isTerminal) {
            logger.debug("Dropping a candidate for {} call {}", call.status.wireValue, callId)
            return
        }
        val participants = participantRepository.findAllByCallId(callId)
        requireParticipant(call, participants, request.userId)

        raise(
            call,
            request.userId,
            CallSignals.ice(request.candidate),
            others(participants, request.userId)
        )
    }

    /** A page of the caller's own call log, newest first. */
    @Transactional(readOnly = true)
    fun history(userId: String, before: String?, limit: Int?): CallHistoryResponse {
        val pageSize = (limit ?: properties.historyPageSize).coerceIn(1, properties.maxHistoryPageSize)
        val cursor = before?.let { raw ->
            val cursorId = raw.toUuid()
            callRepository.findById(cursorId).orElseThrow {
                RelayException(HttpStatus.BAD_REQUEST.value(), "Cursor call $raw does not exist")
            }
        }

        // One more than asked for, so "is there another page" needs no count query.
        val found = callRepository.findHistory(
            userId = userId,
            beforeStartedAt = cursor?.startedAt ?: NEWER_THAN_ANY_CALL,
            beforeId = cursor?.id ?: HIGHEST_UUID,
            limit = Limit.of(pageSize + 1)
        )
        val page = found.take(pageSize)
        if (page.isEmpty()) return CallHistoryResponse(emptyList(), null)

        val participants = participantRepository.findAllByCallIdIn(page.map { it.id }).groupBy { it.callId }
        return CallHistoryResponse(
            calls = page.map { it.toHistoryEntry(participants[it.id].orEmpty(), userId) },
            nextCursor = if (found.size > pageSize) page.last().id.toString() else null
        )
    }

    /** Calls still ringing past the timeout. Read by the sweeper, which then expires them one by one. */
    @Transactional(readOnly = true)
    fun findRungOutCallIds(now: Instant = Instant.now()): List<UUID> =
        callRepository
            .findAllByStatusAndStartedAtBefore(CallStatus.RINGING, now.minus(properties.ringTimeout))
            .map { it.id }

    /**
     * Gives up on a call nobody answered. False when it was already settled — which is the normal
     * outcome of racing an answer, not a failure.
     */
    @Transactional
    fun expireRungOutCall(callId: UUID): Boolean {
        val call = callRepository.findById(callId).orElse(null) ?: return false
        if (call.status != CallStatus.RINGING) return false

        val participants = participantRepository.findAllByCallId(callId)
        terminate(call, participants, CallStatus.MISSED, CallSignals.Reasons.RING_TIMEOUT)

        // Both parties: the caller stops its outgoing UI, the callee stops ringing.
        raise(call, call.initiator, CallSignals.missed(call), participants.map { it.userId })
        // And a push, because the likeliest reason nobody answered is that nobody was there to.
        calleeOf(call, participants)?.let { callee ->
            events.publishEvent(
                CallNotificationRequested(
                    NotificationRequestedEvent.missedCall(
                        recipientId = callee,
                        callId = callId.toString(),
                        callerId = call.initiator,
                        media = call.media.wireValue,
                        requestedAt = call.endedAt ?: Instant.now()
                    )
                )
            )
        }
        logger.info("Call {} rang out unanswered", callId)
        return true
    }

    /**
     * Delivers candidates that were buffered for a call which has since appeared. Closes the narrow
     * window where a candidate is buffered between an invite's commit and its own flush.
     */
    @Transactional(readOnly = true)
    fun flushBufferedCandidates(callId: UUID) {
        val call = callRepository.findById(callId).orElse(null) ?: return
        if (call.status.isTerminal) {
            iceBuffer.drain(callId)
            return
        }
        relayBuffered(callId, participantRepository.findAllByCallId(callId).map { it.userId })
    }

    /** ── internals ──────────────────────────────────────────────────────────────────────── */

    /**
     * A retried invite. Re-publishing while the call still rings is deliberate: the client resent
     * it because it saw no confirmation, and the most likely reason is that the callee never got
     * the first one. A settled call is described rather than re-rung.
     */
    private fun resendOrDescribe(existing: Call, request: InviteCallRequest): CallResponse {
        if (existing.initiator != request.callerId) {
            throw RelayException(
                HttpStatus.CONFLICT.value(),
                "Call ${request.callId} already exists and belongs to someone else"
            )
        }
        val participants = participantRepository.findAllByCallId(existing.id)
        if (existing.status == CallStatus.RINGING) {
            logger.debug("Re-publishing the invite for retried call {}", existing.id)
            raise(
                existing,
                request.callerId,
                CallSignals.invite(existing, request.sdp, ringExpiryOf(existing)),
                others(participants, request.callerId)
            )
        }
        return existing.toResponse(participants)
    }

    /**
     * The busy check — a pair of primary-key inserts, not a query. A user already in a call
     * collides here, and so does the other half of two people dialling each other at once.
     */
    private fun claimBoth(callId: UUID, callerId: String, calleeId: String) {
        try {
            activeCallRepository.saveAllAndFlush(
                listOf(ActiveCall(callerId, callId), ActiveCall(calleeId, callId))
            )
        } catch (ex: DataIntegrityViolationException) {
            throw RelayException(
                HttpStatus.CONFLICT.value(),
                "$callerId or $calleeId is already in a call",
                ex
            )
        }
    }

    private fun sendCallSignalToCallee(
        request: InviteCallRequest,
        call: Call
    ) {
        events.publishEvent(
            CallSignalRaised(
                CallSignalEvent(
                    callId = request.callId,
                    fromUserId = request.callerId,
                    signal = CallSignals.invite(call, request.sdp, ringExpiryOf(call)),
                    recipientIds = listOf(request.calleeId)
                )
            )
        )
    }

    /**
     * The caller's own devices learn the call is ringing. Without this the initiating device
     * has no confirmation the server accepted the invite, and the user's other devices
     * do not know they are busy.
     */
    private fun setCallerDevicesBusy(
        request: InviteCallRequest,
        call: Call
    ) {
        events.publishEvent(
            CallSignalRaised(
                CallSignalEvent(
                    callId = request.callId,
                    fromUserId = request.callerId,
                    signal = CallSignals.state(call),
                    recipientIds = listOf(request.callerId)
                )
            )
        )
    }

    private fun terminate(
        call: Call,
        participants: List<CallParticipant>,
        status: CallStatus,
        reason: String
    ) {
        call.terminate(status, reason)
        participants.forEach { it.leftAt = call.endedAt }
        callRepository.saveAndFlush(call)
        activeCallRepository.deleteAllByCallId(call.id)
    }

    /**
     * Tells a user's *other* devices to stop showing this call. The session that acted is excluded,
     * because the device that just answered must not be told to cancel.
     */
    private fun stopOtherDevices(call: Call, userId: String, sessionId: String, reason: String) {
        events.publishEvent(
            CallSignalRaised(
                CallSignalEvent(
                    callId = call.id.toString(),
                    fromUserId = userId,
                    signal = CallSignals.cancel(reason),
                    recipientIds = listOf(userId),
                    excludeSessionIds = listOf(sessionId)
                )
            )
        )
    }

    /**
     * Raises a signal to a call to all participants except the sender.
     */
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
     * Publishes buffered candidates for a call that now exists. The sender is re-checked against
     * the participant list, because anyone could have buffered a candidate for an id that did not
     * exist yet.
     */
    private fun relayBuffered(callId: UUID, participantIds: List<String>) {
        val buffered = iceBuffer.drain(callId)
        if (buffered.isEmpty()) return
        var relayed = 0
        buffered.forEach { candidate ->
            if (candidate.fromUserId !in participantIds) {
                logger.warn(
                    "Discarding a buffered candidate for {} from non-participant {}",
                    callId, candidate.fromUserId
                )
                return@forEach
            }
            val recipients = participantIds.filter { it != candidate.fromUserId }
            if (recipients.isEmpty()) return@forEach
            events.publishEvent(
                CallSignalRaised(
                    CallSignalEvent(
                        callId = callId.toString(),
                        fromUserId = candidate.fromUserId,
                        signal = CallSignals.ice(candidate.candidate),
                        recipientIds = recipients
                    )
                )
            )
            relayed++
        }
        if (relayed > 0) logger.debug("Relayed {} buffered candidate(s) for call {}", relayed, callId)
    }

    private fun requireCall(rawCallId: String): Call {
        val callId = rawCallId.toUuid()
        return callRepository.findById(callId).orElseThrow {
            RelayException(HttpStatus.NOT_FOUND.value(), "Call $rawCallId not found")
        }
    }

    /**
     * Throws if the user is not a participant of the call.
     */
    private fun requireParticipant(call: Call, participants: List<CallParticipant>, userId: String) {
        if (participants.none { it.userId == userId }) {
            throw RelayException(
                HttpStatus.FORBIDDEN.value(),
                "$userId is not a participant of call ${call.id}"
            )
        }
    }

    /**
     * Throws if the call is not ringing.
     */
    private fun requireRinging(call: Call) {
        if (call.status != CallStatus.RINGING) {
            throw RelayException(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Call ${call.id} is ${call.status.wireValue}, not ringing"
            )
        }
    }

    /**
     * When the sweeper will give up on this call. It travels with the invite so a client can show
     * an honest countdown, and so the gateway can stamp the push it raises for an offline callee.
     */
    private fun ringExpiryOf(call: Call): Instant = call.startedAt.plus(properties.ringTimeout)

    private fun others(participants: List<CallParticipant>, userId: String): List<String> =
        participants.map { it.userId }.filter { it != userId }

    private fun calleeOf(call: Call, participants: List<CallParticipant>): String? =
        participants.map { it.userId }.firstOrNull { it != call.initiator }

    private fun String.toUuid(): UUID = UUID.fromString(this)


    private companion object {

        /**
         * The first page's cursor. Sorting above every real row makes "no cursor" the same query as
         * "a cursor", which is what lets the predicate avoid a null check Postgres cannot type.
         */
        val NEWER_THAN_ANY_CALL: Instant = Instant.parse("9999-12-31T23:59:59Z")

        /** All-ones, which is the maximum under Postgres's byte-wise uuid ordering. */
        val HIGHEST_UUID: UUID = UUID(-1L, -1L)
    }
}

package com.relay.call.service

import com.relay.call.service.sfu.RoomDirectory
import com.relay.common.observability.RequestId
import com.relay.common.observability.RequestIdContext
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The server's own clock on a call.
 *
 * A client cannot be trusted to report a call it never answered — the app was killed, the phone
 * died, the network went away — so nothing but a server-side timer moves a ringing call to
 * `missed`. This also sweeps ICE candidates whose call never appeared, rings out group invitees,
 * and reconciles live group calls against the SFU's own view of the room.
 *
 * It orchestrates rather than transacts: each call is expired in its own transaction on
 * [CallService] or [GroupCallService], through the proxy, so one row that loses a race cannot roll
 * back the rest of the batch. The SFU is only ever consulted *outside* those transactions.
 */
@Component
class CallSweeper(
    private val callService: CallService,
    private val groupCallService: GroupCallService,
    private val roomDirectory: RoomDirectory,
    private val iceBuffer: IceCandidateBuffer
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * A scheduled tick has no ambient request, so it gets a fresh correlation id per sweep. That is
     * what makes one sweep's records — the expiries, the ICE settlements, and any failure among them
     * — groupable in Kibana rather than interleaved with every other tick at the same log level.
     */
    @Scheduled(fixedDelayString = "\${relay.call.sweep-interval}")
    fun sweep() = RequestIdContext.with(RequestId.newId()) {
        expireRungOutCalls()
        expireRungOutGroupCalls()
        expirePendingGroupInvites()
        settleBufferedCandidates()
    }

    /**
     * The missed-webhook backstop, on its own slower cadence: for each live group call, ask the SFU
     * who is actually in the room and remove joined participants it does not know. This is what
     * frees a "busy" user whose token was minted but never used, and what ends a call stuck
     * answered over an empty room.
     */
    @Scheduled(fixedDelayString = "\${relay.call.reconcile-interval}")
    fun reconcileWithSfu() = RequestIdContext.with(RequestId.newId()) {
        try {
            groupCallService.liveGroupCallIds().forEach { callId ->
                // Fetched before the per-call transaction opens — a network call never rides one.
                val identities = try {
                    roomDirectory.participantIdentities(callId.toString()) ?: emptySet()
                } catch (ex: Exception) {
                    logger.warn("Skipping reconciliation of call {} — SFU unreachable: {}", callId, ex.message)
                    return@forEach
                }
                groupCallService.reconcile(callId, identities)
            }
        } catch (ex: Exception) {
            logger.error("SFU reconciliation sweep failed", ex)
        }
    }

    private fun expireRungOutCalls() {
        try {
            val expired = callService.findRungOutCallIds().count { callId ->
                try {
                    callService.expireRungOutCall(callId)
                } catch (ex: OptimisticLockingFailureException) {
                    // It was answered in the moment between the read and the write. Correct
                    // outcome, not an error.
                    logger.debug("Call {} was settled while being expired", callId, ex)
                    false
                }
            }
            if (expired > 0) logger.info("Expired {} unanswered call(s)", expired)
        } catch (ex: Exception) {
            logger.error("Ring-timeout sweep failed", ex)
        }
    }

    /** Group calls nobody joined. Losing the race to a join is the normal outcome, not an error. */
    private fun expireRungOutGroupCalls() {
        try {
            val expired = groupCallService.findRungOutGroupCallIds()
                .count { groupCallService.expireRungOutGroupCall(it) }
            if (expired > 0) logger.info("Expired {} unanswered group call(s)", expired)
        } catch (ex: Exception) {
            logger.error("Group ring-timeout sweep failed", ex)
        }
    }

    /** Individual invitees still ringing on group calls that were answered without them. */
    private fun expirePendingGroupInvites() {
        try {
            val rungOut = groupCallService.findGroupCallIdsWithPendingInvites()
                .sumOf { groupCallService.expirePendingInvites(it) }
            if (rungOut > 0) logger.info("{} group invitee(s) rang out", rungOut)
        } catch (ex: Exception) {
            logger.error("Group invitee ring-timeout sweep failed", ex)
        }
    }

    /**
     * Flush first, expire second. A candidate buffered a moment ago for a call that now exists
     * should be relayed on this pass rather than dropped on it.
     */
    private fun settleBufferedCandidates() {
        try {
            iceBuffer.pendingCallIds().forEach { callId ->
                callService.flushBufferedCandidates(callId)
            }
            iceBuffer.evictExpired()
        } catch (ex: Exception) {
            logger.error("ICE buffer sweep failed", ex)
        }
    }
}

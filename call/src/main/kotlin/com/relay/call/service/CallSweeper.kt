package com.relay.call.service

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
 * `missed`. This also sweeps ICE candidates whose call never appeared.
 *
 * It orchestrates rather than transacts: each call is expired in its own transaction on
 * [CallService], through the proxy, so one row that loses a race cannot roll back the rest of the
 * batch.
 */
@Component
class CallSweeper(
    private val callService: CallService,
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
        settleBufferedCandidates()
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

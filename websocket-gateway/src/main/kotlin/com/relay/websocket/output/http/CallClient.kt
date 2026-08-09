package com.relay.websocket.output.http

import com.relay.common.dto.AcceptCallRequest
import com.relay.common.dto.HangupCallRequest
import com.relay.common.dto.IceCandidateRequest
import com.relay.common.dto.InviteCallRequest
import com.relay.common.dto.RejectCallRequest

/**
 * Outcome of handing one signal to call-service. [Rejected] carries the code that goes into the
 * client's `error` frame.
 */
sealed interface CallSignalResult {

    data object Accepted : CallSignalResult

    data class Rejected(val code: String, val message: String) : CallSignalResult
}

/**
 * Port for reaching call-service, in the shape of `PushSender` in notification-service: the router
 * is written against this, so the transport is one adapter away and a test can record calls without
 * an HTTP stack.
 *
 * **Why HTTP and not Kafka**, when every other inbound frame goes to a topic: the gateway needs an
 * answer. A call that cannot start — the callee is busy, the call is already over — has to fail the
 * client's frame now, while the client still has a pending signal to attribute the error to. Queue
 * latency is invisible for chat and fatal for call setup (`docs/ARCHITECTURE.md` decision 21). The
 * relay back out to participants is the half that stays on Kafka, because there the gateway is the
 * consumer and any node may hold the target socket.
 *
 * Implementations must not throw: a call-service that is down is a [CallSignalResult.Rejected], not
 * an exception escaping into a WebSocket handler.
 */
interface CallClient {

    fun invite(request: InviteCallRequest): CallSignalResult

    fun accept(callId: String, request: AcceptCallRequest): CallSignalResult

    fun reject(callId: String, request: RejectCallRequest): CallSignalResult

    fun hangup(callId: String, request: HangupCallRequest): CallSignalResult

    fun ice(callId: String, request: IceCandidateRequest): CallSignalResult
}

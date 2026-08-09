package com.relay.websocket.output.http

import com.relay.common.dto.AcceptCallRequest
import com.relay.common.dto.HangupCallRequest
import com.relay.common.dto.IceCandidateRequest
import com.relay.common.dto.InviteCallRequest
import com.relay.common.dto.RejectCallRequest
import com.relay.common.dto.ResponseErrorDto
import com.relay.websocket.protocol.ErrorCodes
import com.relay.websocket.util.CallClientProperties
import org.slf4j.LoggerFactory
import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * The only outbound HTTP call the gateway makes.
 *
 * It holds no call state and makes no call decisions: it maps identity onto the request, forwards,
 * and translates a failure into an error code.
 */
@Component
class HttpCallClient(
    @LoadBalanced loadBalancedRestClientBuilder: RestClient.Builder,
    properties: CallClientProperties
) : CallClient {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val client = loadBalancedRestClientBuilder
        .baseUrl(properties.baseUrl)
        .build()

    override fun invite(request: InviteCallRequest): CallSignalResult =
        post("/internal/api/v1/calls/invite", request)

    override fun accept(callId: String, request: AcceptCallRequest): CallSignalResult =
        post("/internal/api/v1/calls/$callId/accept", request)

    override fun reject(callId: String, request: RejectCallRequest): CallSignalResult =
        post("/internal/api/v1/calls/$callId/reject", request)

    override fun hangup(callId: String, request: HangupCallRequest): CallSignalResult =
        post("/internal/api/v1/calls/$callId/hangup", request)

    override fun ice(callId: String, request: IceCandidateRequest): CallSignalResult =
        post("/internal/api/v1/calls/$callId/ice", request)

    private fun post(path: String, body: Any): CallSignalResult =
        try {
            client.post().uri(path).body(body).retrieve().toBodilessEntity()
            CallSignalResult.Accepted
        } catch (ex: RestClientResponseException) {
            CallSignalResult.Rejected(codeFor(ex.statusCode), messageFor(ex))
        } catch (ex: Exception) {
            // Unreachable host, timeout, or no instance registered in Eureka.
            logger.warn("Could not reach call-service for {}: {}", path, ex.message)
            CallSignalResult.Rejected(ErrorCodes.CALL_SIGNAL_FAILED, "Call service is unavailable")
        }

    /**
     * Statuses map one-to-one onto codes, which is why call-service is careful to use a distinct
     * status per failure — see its `CallExceptionHandler`. `NOT_A_PARTICIPANT` and `INVALID_REQUEST`
     * are spelled the same as message-service's codes on purpose: a client already handles them.
     */
    private fun codeFor(status: HttpStatusCode): String = when (status.value()) {
        400 -> "INVALID_REQUEST"
        403 -> "NOT_A_PARTICIPANT"
        404 -> "CALL_NOT_FOUND"
        409 -> "USER_BUSY"
        422 -> "INVALID_CALL_STATE"
        else -> ErrorCodes.CALL_SIGNAL_FAILED
    }

    /**
     * Only the first message, and nothing at all from a 5xx.
     *
     * `ResponseErrorDto` carries a stack trace, and a 500 means the message describes an internal
     * failure the client has no business seeing. Forwarding it verbatim would leak server internals
     * through a socket frame.
     */
    private fun messageFor(ex: RestClientResponseException): String {
        if (ex.statusCode.is5xxServerError) return "Call signal failed"
        val message = runCatching { ex.getResponseBodyAs(ResponseErrorDto::class.java) }
            .getOrNull()
            ?.errorMessage
            ?.firstOrNull()
        return message ?: "Call signal was rejected"
    }
}

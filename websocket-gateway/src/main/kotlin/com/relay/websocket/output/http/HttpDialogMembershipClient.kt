package com.relay.websocket.output.http

import com.relay.common.dto.DialogParticipantsResponse
import com.relay.websocket.protocol.ErrorCodes
import com.relay.websocket.util.MessageClientProperties
import org.slf4j.LoggerFactory
import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * Reads dialog membership off message-service's `/internal` lookup.
 *
 * It shares the gateway's one load-balanced `RestClient.Builder` — and therefore the call path's
 * timeouts — on purpose. Both are short, both are on a path where a client is waiting, and a second
 * builder bean would need qualifiers at every injection point to buy a difference nobody needs.
 */
@Component
class HttpDialogMembershipClient(
    @LoadBalanced loadBalancedRestClientBuilder: RestClient.Builder,
    properties: MessageClientProperties
) : DialogMembershipClient {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val client = loadBalancedRestClientBuilder
        .baseUrl(properties.baseUrl)
        .build()

    override fun participants(dialogId: String, callerId: String): DialogMembershipResult =
        try {
            val response = client.get()
                // Template form, so a client-supplied dialog id is encoded rather than concatenated.
                .uri("/internal/api/v1/dialogs/{dialogId}/participants?callerId={callerId}", dialogId, callerId)
                .retrieve()
                .body(DialogParticipantsResponse::class.java)
            val participants = response?.participantIds?.takeIf { it.isNotEmpty() }
            if (participants == null) {
                logger.warn("message-service returned no participants for dialog {}", dialogId)
                DialogMembershipResult.Rejected(ErrorCodes.INTERNAL, "Dialog membership is unavailable")
            } else {
                DialogMembershipResult.Found(participants)
            }
        } catch (ex: RestClientResponseException) {
            DialogMembershipResult.Rejected(codeFor(ex.statusCode), messageFor(ex.statusCode))
        } catch (ex: Exception) {
            // Unreachable host, timeout, or no message-service instance registered in Eureka.
            logger.warn("Could not reach message-service for dialog {}: {}", dialogId, ex.message)
            DialogMembershipResult.Rejected(ErrorCodes.INTERNAL, "Dialog membership is unavailable")
        }

    /**
     * A 404 means "no such dialog, or not yours" — message-service refuses to distinguish the two so
     * that dialog ids are not enumerable, and the gateway must not undo that by inventing a
     * different code for one of the cases.
     */
    private fun codeFor(status: HttpStatusCode): String = when (status.value()) {
        400 -> ErrorCodes.INVALID_REQUEST
        404 -> ErrorCodes.DIALOG_NOT_FOUND
        else -> ErrorCodes.INTERNAL
    }

    /**
     * Fixed wording rather than the body's. `ResponseErrorDto` can carry a stack trace, and nothing
     * message-service says about a dialog lookup is worth relaying into a socket frame.
     */
    private fun messageFor(status: HttpStatusCode): String = when (status.value()) {
        400 -> "dialog_id is not a valid dialog id"
        404 -> "Dialog not found"
        else -> "Dialog membership is unavailable"
    }
}

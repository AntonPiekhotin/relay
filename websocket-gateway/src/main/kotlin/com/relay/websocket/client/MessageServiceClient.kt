package com.relay.websocket.client

import com.relay.common.dto.MessageResponse
import com.relay.common.dto.ResponseErrorDto
import com.relay.common.dto.SendMessageRequest
import com.relay.common.exception.RelayException
import com.relay.websocket.config.MESSAGE_SERVICE_WEB_CLIENT
import com.relay.websocket.util.MessageServiceProperties
import java.util.concurrent.TimeoutException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper

@Component
class MessageServiceClient(
    @Qualifier(MESSAGE_SERVICE_WEB_CLIENT) private val webClient: WebClient,
    private val props: MessageServiceProperties,
    private val jsonMapper: JsonMapper
) {

    /**
     * 201 for a stored message and 200 for a recognised retry both resolve successfully — the
     * gateway acks either way, since both mean "this message exists".
     */
    fun send(request: SendMessageRequest): Mono<MessageResponse> =
        webClient.post()
            .uri(props.messagesPath)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .onStatus({ it.isError }) { response ->
                response.bodyToMono<String>()
                    .defaultIfEmpty("")
                    .flatMap { errorBody ->
                        Mono.error(
                            RelayException(response.statusCode().value(), summarise(errorBody))
                        )
                    }
            }
            .bodyToMono<MessageResponse>()
            .timeout(props.requestTimeout)
            .onErrorMap(TimeoutException::class.java) {
                RelayException(
                    HttpStatus.GATEWAY_TIMEOUT.value(),
                    "message-service did not respond within ${props.requestTimeout}",
                    it
                )
            }

    /**
     * Upstream errors arrive as [ResponseErrorDto], which carries a full stack trace. Only the
     * messages are kept: this text ends up in an ERROR frame sent to the client, and internal
     * stack traces must never leave the cluster.
     */
    private fun summarise(errorBody: String): String =
        try {
            jsonMapper.readValue(errorBody, ResponseErrorDto::class.java)
                .errorMessage
                .joinToString("; ")
                .ifBlank { GENERIC_FAILURE }
        } catch (ex: Exception) {
            GENERIC_FAILURE
        }

    private companion object {
        const val GENERIC_FAILURE = "message-service rejected the send"
    }
}
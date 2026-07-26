package com.relay.auth.client

import com.relay.auth.config.USER_SERVICE_WEB_CLIENT
import com.relay.auth.util.UserServiceProperties
import com.relay.common.dto.CreateUserRequest
import com.relay.common.exception.RelayException
import java.util.concurrent.TimeoutException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

@Component
class UserServiceClient(
    @Qualifier(USER_SERVICE_WEB_CLIENT) private val webClient: WebClient,
    private val props: UserServiceProperties
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun createUser(request: CreateUserRequest): Mono<Void> =
        webClient.post()
            .uri(props.usersPath)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .onStatus({ it.isError }) { response ->
                response.bodyToMono<String>()
                    .defaultIfEmpty("")
                    .flatMap { errorBody ->
                        Mono.error(
                            RelayException(
                                response.statusCode().value(),
                                "Failed to save user ${request.id} in user service: $errorBody"
                            )
                        )
                    }
            }
            .toBodilessEntity()
            .then()
            .timeout(props.requestTimeout)
            .onErrorMap(TimeoutException::class.java) {
                RelayException(
                    HttpStatus.GATEWAY_TIMEOUT.value(),
                    "Timed out after ${props.requestTimeout} saving user ${request.id} in user service",
                    it
                )
            }
            .doOnSuccess { logger.debug("Saved user {} in user service", request.id) }
}
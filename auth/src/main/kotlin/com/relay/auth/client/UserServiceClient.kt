package com.relay.auth.client

import com.relay.auth.config.USER_SERVICE_REST_CLIENT
import com.relay.auth.util.UserServiceProperties
import com.relay.common.dto.CreateUserRequest
import com.relay.common.exception.RelayException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient

@Component
class UserServiceClient(
    @Qualifier(USER_SERVICE_REST_CLIENT) private val restClient: RestClient,
    private val props: UserServiceProperties
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun createUser(request: CreateUserRequest) {
        try {
            restClient.post()
                .uri(props.usersPath)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus({ it.isError }) { _, response ->
                    val errorBody = response.body.readAllBytes().decodeToString()
                    throw RelayException(
                        response.statusCode.value(),
                        "Failed to save user ${request.id} in user service: $errorBody"
                    )
                }
                .toBodilessEntity()
        } catch (ex: ResourceAccessException) {
            // Only a timeout becomes a 504. A refused connection is a different failure and still
            // falls through to the generic handler, exactly as it did on the reactive client.
            if (ex.cause is HttpTimeoutException || ex.cause is SocketTimeoutException) {
                throw RelayException(
                    HttpStatus.GATEWAY_TIMEOUT.value(),
                    "Timed out after ${props.requestTimeout} saving user ${request.id} in user service",
                    ex
                )
            }
            throw ex
        }
        logger.debug("Saved user {} in user service", request.id)
    }
}
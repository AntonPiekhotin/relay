package com.relay.auth.service

import com.relay.auth.client.UserServiceClient
import com.relay.auth.dto.LoginRequest
import com.relay.auth.dto.RefreshRequest
import com.relay.auth.dto.RegisterRequest
import com.relay.common.dto.CreateUserRequest
import com.relay.common.exception.RelayException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@Service
class AuthService(
    private val keycloakService: KeycloakService,
    private val userServiceClient: UserServiceClient
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun login(request: LoginRequest) =
        keycloakService.login(request)

    fun refresh(request: RefreshRequest) =
        keycloakService.refresh(request.refreshToken)

    fun logout(request: RefreshRequest) =
        keycloakService.logout(request.refreshToken)

    /**
     * Creates the identity in Keycloak and then persists the profile in user-service.
     * If the profile cannot be saved the Keycloak user is deleted again, so registration
     * never leaves behind an identity that has no profile.
     *
     * The Keycloak admin client is blocking, hence the [Schedulers.boundedElastic] hops.
     */
    fun register(request: RegisterRequest): Mono<Void> =
        Mono.fromCallable { keycloakService.registerUser(request) }
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap { userId ->
                userServiceClient.createUser(request.toCreateUserRequest(userId))
                    .onErrorResume { ex -> rollbackRegistration(userId, ex) }
            }

    private fun RegisterRequest.toCreateUserRequest(userId: String) = CreateUserRequest(
        id = userId,
        email = email,
        firstName = firstName,
        lastName = lastName
    )

    private fun rollbackRegistration(userId: String, cause: Throwable): Mono<Void> {
        logger.error("Failed to save user $userId in user service, rolling back Keycloak user", cause)
        return Mono.fromRunnable<Void> { keycloakService.deleteUser(userId) }
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume { rollbackEx ->
                logger.error("Rollback failed: could not delete orphaned Keycloak user $userId", rollbackEx)
                Mono.empty()
            }
            .then(Mono.error(registrationFailure(cause)))
    }

    /** Keeps the status reported by user-service (e.g. 409 on a duplicate profile) when there is one. */
    private fun registrationFailure(cause: Throwable): Throwable =
        cause as? RelayException
            ?: RelayException(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Failed to register user: ${cause.message}",
                cause
            )
}

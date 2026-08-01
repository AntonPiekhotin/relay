package com.relay.auth.service

import com.relay.auth.client.UserServiceClient
import com.relay.auth.dto.ChangePasswordRequest
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
     * Password changes live here rather than in user-service because Keycloak owns the credential —
     * user-db has no password column to update (ARCHITECTURE.md §8.1).
     *
     * The current password is re-verified with a password grant, the only way to check a credential
     * through Keycloak's API, before the admin client overwrites it. Holding a valid access token is
     * not sufficient on its own: a leaked token would otherwise be enough to lock the owner out.
     *
     * Existing sessions are deliberately left alone, so the caller is not signed out of the device
     * they just used. Revoking every other session on a password change is the natural follow-up
     * and needs a per-session view we do not keep yet.
     */
    fun changePassword(userId: String, username: String, request: ChangePasswordRequest): Mono<Void> {
        if (request.newPassword == request.currentPassword) {
            return Mono.error(
                RelayException(
                    HttpStatus.BAD_REQUEST.value(),
                    "New password must differ from the current one"
                )
            )
        }
        return keycloakService.login(LoginRequest(username, request.currentPassword))
            .onErrorMap(::currentPasswordRejected)
            .then(
                Mono.fromRunnable<Void> { keycloakService.resetPassword(userId, request.newPassword) }
                    .subscribeOn(Schedulers.boundedElastic())
            )
            .doOnSuccess { logger.info("Password changed for user {}", userId) }
    }

    /**
     * A failed verification is reported as a plain 401 instead of Keycloak's `invalid_grant` body:
     * the caller does not need the token endpoint's internals, and the raw body would be echoed
     * straight back to a client.
     */
    private fun currentPasswordRejected(cause: Throwable): Throwable =
        if (cause is RelayException && cause.statusCode == HttpStatus.UNAUTHORIZED.value()) {
            RelayException(HttpStatus.UNAUTHORIZED.value(), "Current password is incorrect", cause)
        } else {
            cause
        }

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

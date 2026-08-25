package com.relay.auth.service

import com.relay.auth.client.UserServiceClient
import com.relay.auth.dto.ChangePasswordRequest
import com.relay.auth.dto.LoginRequest
import com.relay.auth.dto.RefreshRequest
import com.relay.auth.dto.RegisterRequest
import com.relay.auth.dto.TokenResponse
import com.relay.common.dto.CreateUserRequest
import com.relay.common.exception.RelayException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val keycloakService: KeycloakService,
    private val userServiceClient: UserServiceClient
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun login(request: LoginRequest): TokenResponse =
        keycloakService.login(request)

    fun refresh(request: RefreshRequest): TokenResponse =
        keycloakService.refresh(request.refreshToken)

    fun logout(request: RefreshRequest) =
        keycloakService.logout(request.refreshToken)

    fun changePassword(userId: String, username: String, request: ChangePasswordRequest) {
        if (request.newPassword == request.currentPassword) {
            throw RelayException(
                HttpStatus.BAD_REQUEST.value(),
                "New password must differ from the current one"
            )
        }
        try {
            keycloakService.login(LoginRequest(username, request.currentPassword))
        } catch (ex: Exception) {
            throw currentPasswordRejected(ex)
        }
        keycloakService.resetPassword(userId, request.newPassword)
        logger.info("Password changed for user {}", userId)
    }

    private fun currentPasswordRejected(cause: Throwable): Throwable =
        if (cause is RelayException && cause.statusCode == HttpStatus.UNAUTHORIZED.value()) {
            RelayException(HttpStatus.UNAUTHORIZED.value(), "Current password is incorrect", cause)
        } else {
            cause
        }

    fun register(request: RegisterRequest) {
        val userId = keycloakService.registerUser(request)
        try {
            userServiceClient.createUser(request.toCreateUserRequest(userId))
        } catch (ex: Exception) {
            rollbackRegistration(userId, ex)
        }
    }

    private fun RegisterRequest.toCreateUserRequest(userId: String) = CreateUserRequest(
        id = userId,
        email = email,
        firstName = firstName,
        lastName = lastName
    )

    private fun rollbackRegistration(userId: String, cause: Throwable): Nothing {
        logger.error("Failed to save user $userId in user service, rolling back Keycloak user", cause)
        try {
            keycloakService.deleteUser(userId)
        } catch (rollbackEx: Exception) {
            logger.error("Rollback failed: could not delete orphaned Keycloak user $userId", rollbackEx)
        }
        throw registrationFailure(cause)
    }

    private fun registrationFailure(cause: Throwable): Throwable =
        cause as? RelayException
            ?: RelayException(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Failed to register user: ${cause.message}",
                cause
            )
}
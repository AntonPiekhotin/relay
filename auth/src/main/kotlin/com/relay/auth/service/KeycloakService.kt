package com.relay.auth.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.relay.auth.config.KEYCLOAK_WEB_CLIENT
import com.relay.auth.dto.LoginRequest
import com.relay.auth.dto.RegisterRequest
import com.relay.auth.dto.TokenResponse
import com.relay.common.exception.RelayException
import com.relay.common.model.Role
import com.relay.auth.util.KeycloakProperties
import com.relay.auth.util.UserCredentials
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.keycloak.admin.client.CreatedResponseUtil
import org.keycloak.admin.client.Keycloak
import org.keycloak.representations.idm.UserRepresentation
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

private const val GRANT_TYPE = "grant_type"
private const val PASSWORD = "password"
private const val CLIENT_ID = "client_id"
private const val CLIENT_SECRET = "client_secret"
private const val REFRESH_TOKEN = "refresh_token"
private const val USERNAME = "username"
private const val SCOPE = "scope"
private const val OPENID = "openid"

@Service
class KeycloakService(
    private val keycloak: Keycloak,
    private val props: KeycloakProperties,
    private val mapper: ObjectMapper,
    @Qualifier(KEYCLOAK_WEB_CLIENT) private val webClient: WebClient
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val tokenUrl = "${props.url}/realms/${props.realm}/protocol/openid-connect/token"
    private val logoutUrl = "${props.url}/realms/${props.realm}/protocol/openid-connect/logout"

    /** Creates the user and assigns its client role, returning the new Keycloak user id. */
    fun registerUser(request: RegisterRequest): String = with(request) {
        val user = UserRepresentation().apply {
            username = request.email
            firstName = request.firstName
            lastName = request.lastName
            email = request.email
            credentials = mutableListOf(UserCredentials.createPasswordCredentials(password))
            isEnabled = true
        }
        val response = keycloak.realm(props.realm).users().create(user)
        if (response.status != 200 && response.status != 201) {
            processError(response, "create user")
        }
        val userId = CreatedResponseUtil.getCreatedId(response)
        try {
            assignClientRole(userId)
        } catch (ex: Exception) {
            rollbackUserCreation(userId, ex)
        }
        userId
    }

    private fun rollbackUserCreation(userId: String, cause: Exception): Nothing {
        logger.error("Registration failed after user $userId was created, rolling back", cause)
        try {
            deleteUser(userId)
        } catch (rollbackEx: Exception) {
            logger.error("Rollback failed: could not delete orphaned user $userId", rollbackEx)
        }
        throw RelayException(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Failed to register user: ${cause.message}",
            cause
        )
    }

    /**
     * Keycloak reports failures as a JSON body with an `errorMessage`, but not always — a policy
     * rejection, a proxy error page or an empty body all end up here too, so neither the read nor
     * the parse may be allowed to mask the real status with a 500.
     */
    private fun processError(response: Response, action: String): Nothing {
        val errorBody = runCatching { response.readEntity(String::class.java) }.getOrNull().orEmpty()
        val errorMsg = runCatching {
            (mapper.readValue(errorBody, Map::class.java) as Map<*, *>)["errorMessage"]
        }.getOrNull() ?: errorBody.ifBlank { "Unknown error" }
        throw RelayException(response.status, "Failed to $action: $errorMsg")
    }

    private fun assignClientRole(
        userId: String,
        role: Role = Role.USER
    ) {
        val realmResource = keycloak.realm(props.realm)
        val client = realmResource.clients()
            .findByClientId(props.clientId)
            .firstOrNull()
            ?: throw RelayException(HttpStatus.NOT_IMPLEMENTED.value(), "Client not found")
        val clientResource = realmResource.clients().get(client.id)
        val role = clientResource.roles().get(role.name).toRepresentation()
        realmResource.users()
            .get(userId)
            .roles()
            .clientLevel(client.id)
            .add(listOf(role))
    }

    fun login(request: LoginRequest): Mono<TokenResponse> {
        return webClient.post()
            .uri(tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(
                BodyInserters.fromFormData(GRANT_TYPE, PASSWORD)
                    .with(CLIENT_ID, props.clientId)
                    .with(CLIENT_SECRET, props.clientSecret)
                    .with(USERNAME, request.email)
                    .with(PASSWORD, request.password)
                    .with(SCOPE, OPENID)
            )
            .retrieve()
            .onStatus({ it.isError }) { response ->
                response.bodyToMono<String>().flatMap { errorBody ->
                    Mono.error(RelayException(response.statusCode().value(), "Login failed: $errorBody"))
                }
            }
            .bodyToMono<TokenResponse>()
    }

    fun refresh(refreshToken: String): Mono<TokenResponse> {
        return webClient.post()
            .uri(tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(
                BodyInserters.fromFormData(GRANT_TYPE, REFRESH_TOKEN)
                    .with(CLIENT_ID, props.clientId)
                    .with(CLIENT_SECRET, props.clientSecret)
                    .with(REFRESH_TOKEN, refreshToken)
            )
            .retrieve()
            .onStatus({ it.isError }) { response ->
                response.bodyToMono<String>().flatMap { errorBody ->
                    Mono.error(RelayException(response.statusCode().value(), "Refresh failed: $errorBody"))
                }
            }
            .bodyToMono<TokenResponse>()
    }

    fun logout(refreshToken: String): Mono<Void> {
        return webClient.post()
            .uri(logoutUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(
                BodyInserters.fromFormData(CLIENT_ID, props.clientId)
                    .with(CLIENT_SECRET, props.clientSecret)
                    .with(REFRESH_TOKEN, refreshToken)
            )
            .retrieve()
            .onStatus({ it.isError }) { response ->
                response.bodyToMono<String>().flatMap { errorBody ->
                    Mono.error(RelayException(response.statusCode().value(), "Logout failed: $errorBody"))
                }
            }
            .toBodilessEntity()
            .then()
    }

    /**
     * Overwrites the credential through the admin API. Blocking, like every admin-client call.
     *
     * Keycloak enforces its own realm password policy here (length, history, reuse), which is why a
     * [WebApplicationException] is unpacked into the status and message it carries instead of
     * becoming a 500 — the client needs to be told *why* the new password was refused.
     */
    fun resetPassword(userId: String, newPassword: String) {
        try {
            keycloak.realm(props.realm)
                .users()
                .get(userId)
                .resetPassword(UserCredentials.createPasswordCredentials(newPassword))
        } catch (ex: WebApplicationException) {
            processError(ex.response, "change password")
        } catch (ex: Exception) {
            throw RelayException(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Failed to change password for user $userId",
                ex
            )
        }
        logger.debug("Reset password for user {}", userId)
    }

    fun deleteUser(userId: String) {
        val usersResource = keycloak.realm(props.realm).users()
        try {
            usersResource.get(userId).remove()
        } catch (ex: Exception) {
            throw RelayException(statusCode = 500, message = "Failed to delete user $userId", cause = ex)
        }
    }
}

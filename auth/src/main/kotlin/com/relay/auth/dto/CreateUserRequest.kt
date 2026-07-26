package com.relay.auth.dto

/**
 * Profile payload sent to user-service once the identity exists in Keycloak.
 *
 * [id] is the Keycloak user id, i.e. the `sub` claim of every token issued for this
 * user, so user-service can key the profile by it without a second lookup.
 */
data class CreateUserRequest(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String
) {
    companion object {
        fun of(userId: String, request: RegisterRequest) = CreateUserRequest(
            id = userId,
            email = request.email,
            firstName = request.firstName,
            lastName = request.lastName
        )
    }
}
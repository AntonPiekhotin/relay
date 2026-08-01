package com.relay.auth.util

import com.relay.common.exception.RelayException
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt

/** The Keycloak user id, which is what the admin API keys credentials by. */
fun Jwt.userId(): String =
    subject ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no subject")

/**
 * The Keycloak username. Registration sets it to the user's email, and it is what a password grant
 * expects — so verifying a password never needs a round trip to the profile service to find out who
 * the caller is.
 */
fun Jwt.username(): String =
    getClaimAsString("preferred_username")
        ?: getClaimAsString("email")
        ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no username")
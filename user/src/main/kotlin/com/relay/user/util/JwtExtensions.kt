package com.relay.user.util

import com.relay.common.exception.RelayException
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt

/**
 * The `sub` claim is the Keycloak user id, which is also the profile's primary key — so "me" never
 * comes from the request body or a path variable, and a client cannot act as somebody else.
 * Without a subject there is no user to act as, so refuse rather than guess.
 */
fun Jwt.userId(): String =
    subject ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no subject")
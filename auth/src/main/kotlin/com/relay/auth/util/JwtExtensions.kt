package com.relay.auth.util

import com.relay.common.exception.RelayException
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt

fun Jwt.userId(): String =
    subject ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no subject")

fun Jwt.username(): String =
    getClaimAsString("preferred_username")
        ?: getClaimAsString("email")
        ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no username")
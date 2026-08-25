package com.relay.user.util

import com.relay.common.exception.RelayException
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt

fun Jwt.userId(): String =
    subject ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no subject")
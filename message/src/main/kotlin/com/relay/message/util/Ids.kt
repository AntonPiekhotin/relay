package com.relay.message.util

import com.relay.common.exception.RelayException
import java.util.UUID
import org.springframework.http.HttpStatus

/**
 * Every id reaching this service is a string — off a JWT claim, a path variable, a query parameter,
 * or a Kafka command — and every one of them has to become a `UUID` before it touches a query.
 * A malformed one is the client's mistake, so it is a `400` (`INVALID_REQUEST` on the wire) rather
 * than the `500` that `UUID.fromString` would otherwise produce.
 */
fun String.toUuidOrBadRequest(field: String): UUID =
    try {
        UUID.fromString(this)
    } catch (ex: IllegalArgumentException) {
        throw RelayException(HttpStatus.BAD_REQUEST.value(), "$field is not a valid id: $this", ex)
    }

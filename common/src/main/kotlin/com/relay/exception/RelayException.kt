package com.relay.exception

class RelayException(
    val statusCode: Int,
    override val message: String? = null,
    override val cause: Throwable? = null
) : RuntimeException(message, cause)


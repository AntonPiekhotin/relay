package com.relay.common.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Adopts the inbound [RequestId.HEADER] or mints one, puts it in the MDC for the duration of the
 * request, and echoes it back on the response.
 *
 * Registered at `Ordered.HIGHEST_PRECEDENCE` so that every later filter — Spring Security
 * included — logs with the id already in scope.
 *
 * The echo is not decoration: it is how a caller learns which id to search for, which turns
 * "reproduce it and dig through nine log files" into one Kibana filter.
 *
 * Nothing in here is allowed to throw. A filter at the head of the chain that fails would turn
 * every request in every service into a 500, and a correlation id is never worth that.
 */
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        var applied = false
        try {
            val inbound = request.getHeader(RequestId.HEADER)
            // Length-capped and generated when absent: the header is client-controlled, and an
            // unbounded value would be copied onto every log record of the request.
            val id = if (!inbound.isNullOrBlank() && inbound.length <= MAX_INBOUND_LENGTH) {
                inbound
            } else {
                RequestId.newId()
            }
            RequestIdContext.put(RequestId.MDC_REQUEST_ID, id)
            applied = true
            if (!response.isCommitted) response.setHeader(RequestId.HEADER, id)
        } catch (e: Exception) {
            logger.debug("Could not establish a request id; continuing without one", e)
        }

        try {
            filterChain.doFilter(request, response)
        } finally {
            if (applied) RequestIdContext.clear()
        }
    }

    private companion object {
        const val MAX_INBOUND_LENGTH = 128
    }
}

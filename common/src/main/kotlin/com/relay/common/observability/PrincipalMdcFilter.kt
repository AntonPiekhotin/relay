package com.relay.common.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

private const val ANONYMOUS = "anonymousUser"

/**
 * Adds the authenticated user to the MDC, so a log record says *who* as well as *which request*.
 *
 * Separate from [RequestIdFilter] because of ordering: the identity only exists once Spring
 * Security has authenticated the request, so this has to run after `springSecurityFilterChain`
 * (registered at `SecurityProperties.DEFAULT_FILTER_ORDER`, -100) while the request id has to be
 * in scope well before it.
 *
 * Reads `Authentication.name` rather than unwrapping a `Jwt`: for the `JwtAuthenticationToken`
 * these services use, that is the `sub` claim — the same value `Jwt.userId()` returns in
 * user-service and auth-service — and it keeps `spring-security-oauth2-jose` off this module's
 * compile classpath.
 *
 * Endpoints under the `/internal` prefix are service-to-service and carry caller-supplied identity
 * rather than a token, so records from those requests carry a requestId and no userId. That is
 * correct: inventing one here would be asserting an identity nobody authenticated.
 */
class PrincipalMdcFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        var applied = false
        try {
            val authentication = SecurityContextHolder.getContext().authentication
            val name = authentication?.name
            if (authentication != null &&
                authentication.isAuthenticated &&
                !name.isNullOrBlank() &&
                name != ANONYMOUS
            ) {
                RequestIdContext.put(RequestId.MDC_USER_ID, name)
                applied = true
            }
        } catch (e: Exception) {
            logger.debug("Could not resolve the authenticated principal for the MDC", e)
        }

        try {
            filterChain.doFilter(request, response)
        } finally {
            // Only this key: RequestIdFilter owns the request id and clears it in its own finally.
            if (applied) MDC.remove(RequestId.MDC_USER_ID)
        }
    }

}

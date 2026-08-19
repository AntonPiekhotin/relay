package com.relay.common.observability

import jakarta.servlet.Filter
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered

/**
 * Registers the two MDC filters in any servlet service that has this module on its classpath, so
 * correlated logs cost a consuming service nothing — user-service and notification-service need no
 * code of their own at all.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(Filter::class)
class RequestIdWebAutoConfiguration {

    /**
     * `HIGHEST_PRECEDENCE`: everything downstream, Spring Security included, should log with the id
     * already in scope.
     */
    @Bean
    @ConditionalOnMissingBean(RequestIdFilter::class)
    fun relayRequestIdFilter(): FilterRegistrationBean<RequestIdFilter> =
        FilterRegistrationBean(RequestIdFilter()).apply {
            order = Ordered.HIGHEST_PRECEDENCE
        }

    /**
     * `-99`, i.e. immediately after `springSecurityFilterChain` at
     * `SecurityProperties.DEFAULT_FILTER_ORDER` (-100) — the authenticated identity does not exist
     * before that runs. Guarded separately so a service without Spring Security on the classpath
     * still gets the request id from the filter above.
     */
    @Bean
    @ConditionalOnMissingBean(PrincipalMdcFilter::class)
    @ConditionalOnClass(name = ["org.springframework.security.core.context.SecurityContextHolder"])
    fun relayPrincipalMdcFilter(): FilterRegistrationBean<PrincipalMdcFilter> =
        FilterRegistrationBean(PrincipalMdcFilter()).apply {
            order = SECURITY_FILTER_ORDER + 1
        }

    private companion object {
        const val SECURITY_FILTER_ORDER = -100
    }
}

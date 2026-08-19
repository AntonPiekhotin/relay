package com.relay.common.observability

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.web.client.RestClient

/**
 * Publishes the outbound-propagation interceptor as a bean. Attaching it is left to each caller,
 * because the two services that make internal calls build their own `RestClient.Builder` rather
 * than using Boot's — see [RequestIdClientHttpRequestInterceptor] for why a `RestClientCustomizer`
 * cannot do this automatically.
 */
@AutoConfiguration
@ConditionalOnClass(RestClient::class)
class RequestIdHttpClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RequestIdClientHttpRequestInterceptor::class)
    fun relayRequestIdClientHttpRequestInterceptor(): RequestIdClientHttpRequestInterceptor =
        RequestIdClientHttpRequestInterceptor()
}

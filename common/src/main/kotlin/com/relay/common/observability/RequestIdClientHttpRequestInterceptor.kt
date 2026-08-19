package com.relay.common.observability

import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/**
 * Copies the in-scope correlation id onto an outbound HTTP call, so the callee's own
 * [RequestIdFilter] adopts it instead of minting a second one.
 *
 * This is published as a plain bean rather than applied through a `RestClientCustomizer`, because
 * a customizer only reaches the `RestClient.Builder` that Boot auto-configures. Both callers here
 * — auth-service's user client and the gateway's call/message clients — build their own via a bare
 * `RestClient.builder()` to avoid an Eureka bootstrap deadlock, and are documented as doing so
 * deliberately. So each attaches this explicitly, on the `@LoadBalanced` builder only: the plain
 * `@Primary` builder exists for Eureka's own registry traffic, which nobody wants correlated.
 */
class RequestIdClientHttpRequestInterceptor : ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val id = RequestId.current()
        // Never overwrite: a caller that set the header explicitly means it. Checked via getFirst
        // rather than containsKey because HttpHeaders no longer implements MultiValueMap in
        // Spring Framework 7.
        if (id != null && request.headers.getFirst(RequestId.HEADER) == null) {
            request.headers.add(RequestId.HEADER, id)
        }
        return execution.execute(request, body)
    }
}

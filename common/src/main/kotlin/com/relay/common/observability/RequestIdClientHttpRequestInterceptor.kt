package com.relay.common.observability

import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/**
 * Copies the in-scope correlation id onto an outbound HTTP call, so the callee's own
 * [RequestIdFilter] adopts it instead of minting a second one.
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

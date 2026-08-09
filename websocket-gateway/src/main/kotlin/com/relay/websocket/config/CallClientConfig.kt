package com.relay.websocket.config

import com.relay.websocket.util.CallClientProperties
import java.net.http.HttpClient
import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient

/**
 * The gateway's only outbound HTTP client.
 *
 * `@LoadBalanced` is what lets the base URL be `lb://call` — Spring Cloud's interceptor resolves the
 * service id through Eureka and rewrites the URI, so the gateway never learns call-service's port
 * (it is random, like every other service here).
 *
 * The JDK client rather than the simple one: it pools connections and speaks HTTP/2, which matters
 * on the one path that sends dozens of small requests in a burst — a trickle-ICE gathering round.
 */
@Configuration
class CallClientConfig {

    /**
     * Eureka's own transport picks up a `RestClient.Builder` from the context
     * (`ObjectProvider.getIfAvailable`). If the load-balanced builder is the only candidate,
     * Eureka's registry calls go through the load balancer — which needs Eureka to resolve
     * anything — and startup deadlocks. This plain builder, marked [Primary], is the one that
     * lookup must find; anything talking to another service injects with [LoadBalanced] instead.
     */
    @Bean
    @Primary
    fun plainRestClientBuilder(): RestClient.Builder = RestClient.builder()

    @Bean
    @LoadBalanced
    fun loadBalancedRestClientBuilder(properties: CallClientProperties): RestClient.Builder {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(properties.readTimeout)
        }
        return RestClient.builder().requestFactory(requestFactory)
    }
}

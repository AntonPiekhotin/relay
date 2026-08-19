package com.relay.auth.config

import com.relay.auth.util.UserServiceProperties
import com.relay.common.observability.RequestIdClientHttpRequestInterceptor
import java.net.http.HttpClient
import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient

const val USER_SERVICE_REST_CLIENT = "userServiceRestClient"

@Configuration
class UserServiceConfig {

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

    /**
     * Resolves `lb://<service-id>` URIs through Eureka. Spring Cloud's
     * `LoadBalancerRestClientBuilderBeanPostProcessor` attaches the load balancing
     * interceptor to any builder bean annotated with [LoadBalanced].
     */
    @Bean
    @LoadBalanced
    fun loadBalancedRestClientBuilder(): RestClient.Builder = RestClient.builder()

    @Bean(USER_SERVICE_REST_CLIENT)
    fun userServiceRestClient(
        @LoadBalanced builder: RestClient.Builder,
        props: UserServiceProperties,
        requestId: RequestIdClientHttpRequestInterceptor
    ): RestClient = builder
        .baseUrl(props.baseUrl)
        // Forwards this request's correlation id to user-service, so a registration reads as one
        // chain across auth and user in Kibana instead of two unrelated ones. Attached here rather
        // than through a RestClientCustomizer because Boot only applies those to the builder it
        // auto-configures, and the builders above are hand-built for the deadlock reason documented
        // on plainRestClientBuilder. Deliberately not on the @Primary plain builder: that one
        // carries Eureka's own registry traffic, which nobody wants correlated.
        .requestInterceptor(requestId)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                    .connectTimeout(props.connectTimeout)
                    .build()
            ).apply { setReadTimeout(props.requestTimeout) }
        )
        .build()
}
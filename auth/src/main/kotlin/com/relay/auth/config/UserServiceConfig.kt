package com.relay.auth.config

import com.relay.auth.util.UserServiceProperties
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
        props: UserServiceProperties
    ): RestClient = builder
        .baseUrl(props.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                    .connectTimeout(props.connectTimeout)
                    .build()
            ).apply { setReadTimeout(props.requestTimeout) }
        )
        .build()
}
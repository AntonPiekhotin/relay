package com.relay.auth.config

import com.relay.auth.util.UserServiceProperties
import java.net.http.HttpClient
import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient

const val USER_SERVICE_REST_CLIENT = "userServiceRestClient"

@Configuration
class UserServiceConfig {

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
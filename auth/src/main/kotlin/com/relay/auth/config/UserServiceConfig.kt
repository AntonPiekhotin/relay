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
        // The reactive client applied this budget with `.timeout()` on the returned Mono. A blocking
        // client has to push it down to the transport instead, or a silent peer parks the calling
        // thread indefinitely.
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newHttpClient())
                .apply { setReadTimeout(props.requestTimeout) }
        )
        .build()
}
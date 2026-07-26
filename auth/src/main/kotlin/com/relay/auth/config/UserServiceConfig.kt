package com.relay.auth.config

import com.relay.auth.util.UserServiceProperties
import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

const val USER_SERVICE_WEB_CLIENT = "userServiceWebClient"

@Configuration
class UserServiceConfig {

    /**
     * Resolves `lb://<service-id>` URIs through Eureka. Spring Cloud's
     * `LoadBalancerWebClientBuilderBeanPostProcessor` attaches the load balancing
     * filter to any builder bean annotated with [LoadBalanced].
     */
    @Bean
    @LoadBalanced
    fun loadBalancedWebClientBuilder(): WebClient.Builder = WebClient.builder()

    @Bean(USER_SERVICE_WEB_CLIENT)
    fun userServiceWebClient(
        @LoadBalanced builder: WebClient.Builder,
        props: UserServiceProperties
    ): WebClient = builder.baseUrl(props.baseUrl).build()
}
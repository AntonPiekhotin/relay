package com.relay.websocket.config

import com.relay.websocket.util.MessageServiceProperties
import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

const val MESSAGE_SERVICE_WEB_CLIENT = "messageServiceWebClient"

@Configuration
class MessageServiceConfig {

    /**
     * Resolves `lb://<service-id>` through Eureka. Spring Cloud's
     * `LoadBalancerWebClientBuilderBeanPostProcessor` attaches the load balancing filter to any
     * builder bean annotated with [LoadBalanced].
     */
    @Bean
    @LoadBalanced
    fun loadBalancedWebClientBuilder(): WebClient.Builder = WebClient.builder()

    @Bean(MESSAGE_SERVICE_WEB_CLIENT)
    fun messageServiceWebClient(
        @LoadBalanced builder: WebClient.Builder,
        props: MessageServiceProperties
    ): WebClient = builder.baseUrl(props.baseUrl).build()
}
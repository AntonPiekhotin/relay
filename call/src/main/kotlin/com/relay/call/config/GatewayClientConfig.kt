package com.relay.call.config

import com.relay.common.observability.RequestIdClientHttpRequestInterceptor
import java.net.http.HttpClient
import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
class GatewayClientConfig {

    @Bean
    @Primary
    fun plainRestClientBuilder(): RestClient.Builder = RestClient.builder()

    @Bean
    @LoadBalanced
    fun loadBalancedRestClientBuilder(
        properties: GatewayClientProperties,
        requestId: RequestIdClientHttpRequestInterceptor,
    ): RestClient.Builder {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(properties.readTimeout)
        }
        return RestClient.builder()
            .requestFactory(requestFactory)
            .requestInterceptor(requestId)
    }
}

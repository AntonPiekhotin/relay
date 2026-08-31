package com.relay.call.output.http

import com.relay.call.config.GatewayClientProperties
import com.relay.call.service.SessionDirectory
import org.slf4j.LoggerFactory
import org.springframework.cloud.client.loadbalancer.LoadBalanced
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class HttpSessionDirectory(
    @LoadBalanced loadBalancedRestClientBuilder: RestClient.Builder,
    properties: GatewayClientProperties
) : SessionDirectory {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val client = loadBalancedRestClientBuilder
        .baseUrl(properties.baseUrl)
        .build()

    override fun onlineAmong(userIds: Collection<String>): Set<String>? =
        try {
            client.get()
                .uri { uri ->
                    uri.path("/internal/api/v1/sessions/online")
                        .queryParam("userId", *userIds.toTypedArray())
                        .build()
                }
                .retrieve()
                .body(object : ParameterizedTypeReference<Set<String>>() {})
        } catch (ex: Exception) {
            logger.warn("Could not ask websocket-gateway who is online: {}", ex.message)
            null
        }
}

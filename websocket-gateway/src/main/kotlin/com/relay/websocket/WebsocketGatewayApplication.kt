package com.relay.websocket

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class WebsocketGatewayApplication

fun main(args: Array<String>) {
    runApplication<WebsocketGatewayApplication>(*args)
}

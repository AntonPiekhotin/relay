package com.relay.message

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MessageApplication

fun main(args: Array<String>) {
    runApplication<MessageApplication>(*args)
}

package com.relay.call

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CallApplication

fun main(args: Array<String>) {
    runApplication<CallApplication>(*args)
}

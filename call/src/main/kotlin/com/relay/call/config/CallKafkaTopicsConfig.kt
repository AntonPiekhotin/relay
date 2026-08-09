package com.relay.call.config

import com.relay.common.event.KafkaTopics
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * The topics this service produces to, declared here rather than left to broker auto-creation —
 * the broker's `num.partitions` default of 1 would cap listener concurrency at one thread for
 * every consumer group on them.
 *
 * `call.signal` used to be declared by websocket-gateway, which only consumes it. It moved here
 * when this service became its producer, so the rule that a topic is declared by whoever produces
 * to it holds again. `notifications` stays declared by the gateway, which is still its main
 * producer.
 */
@Configuration
class CallKafkaTopicsConfig {

    @Bean
    fun callSignalTopic(): NewTopic =
        TopicBuilder.name(KafkaTopics.CALL_SIGNAL)
            .partitions(KafkaTopics.PARTITIONS)
            .replicas(1)
            .build()
}

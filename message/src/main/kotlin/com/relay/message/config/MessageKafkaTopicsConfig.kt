package com.relay.message.config

import com.relay.common.event.KafkaTopics
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * The topic this service produces to, declared rather than left to broker auto-creation —
 * the broker's `num.partitions` default of 1 would cap listener concurrency at one thread
 * for every consumer group on it, whatever the listeners ask for.
 */
@Configuration
class MessageKafkaTopicsConfig {

    @Bean
    fun messagesDeliveryTopic(): NewTopic =
        TopicBuilder.name(KafkaTopics.MESSAGES_DELIVERY)
            .partitions(KafkaTopics.PARTITIONS)
            .replicas(1)
            .build()
}

package com.relay.message.config

import com.relay.common.event.KafkaTopics
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class MessageKafkaTopicsConfig {

    @Bean
    fun messagesDeliveryTopic(): NewTopic =
        TopicBuilder.name(KafkaTopics.MESSAGES_DELIVERY)
            .partitions(KafkaTopics.PARTITIONS)
            .replicas(1)
            .build()
}

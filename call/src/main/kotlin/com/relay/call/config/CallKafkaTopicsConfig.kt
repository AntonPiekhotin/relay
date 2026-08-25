package com.relay.call.config

import com.relay.common.event.KafkaTopics
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class CallKafkaTopicsConfig {

    @Bean
    fun callSignalTopic(): NewTopic =
        TopicBuilder.name(KafkaTopics.CALL_SIGNAL)
            .partitions(KafkaTopics.PARTITIONS)
            .replicas(1)
            .build()
}

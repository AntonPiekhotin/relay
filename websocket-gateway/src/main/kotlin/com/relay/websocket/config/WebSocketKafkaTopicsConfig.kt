package com.relay.websocket.config

import com.relay.common.event.KafkaTopics
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * The topics this service touches, declared rather than left to broker auto-creation.
 *
 * Auto-creation would use the broker's `num.partitions` default, which is 1 — and a single
 * partition caps the whole consumer group at one thread no matter what concurrency the
 * listeners ask for. Declaring them here makes the partition count a decision instead of an
 * accident. Topics that already exist are left alone.
 */
@Configuration
class WebSocketKafkaTopicsConfig {

    @Bean
    fun messagesIncomingTopic(): NewTopic = topic(KafkaTopics.MESSAGES_INCOMING)

    @Bean
    fun messagesReadTopic(): NewTopic = topic(KafkaTopics.MESSAGES_READ)

    @Bean
    fun notificationsTopic(): NewTopic = topic(KafkaTopics.NOTIFICATIONS)

    @Bean
    fun notificationsDeliveryTopic(): NewTopic = topic(KafkaTopics.NOTIFICATIONS_DELIVERY)

    private fun topic(name: String): NewTopic =
        TopicBuilder.name(name)
            .partitions(KafkaTopics.PARTITIONS)
            .replicas(1)
            .build()
}

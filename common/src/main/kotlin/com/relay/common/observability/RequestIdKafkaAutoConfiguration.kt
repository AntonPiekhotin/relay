package com.relay.common.observability

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.RecordInterceptor

/**
 * Wires correlation ids through Kafka in both directions with **no per-service code**: Boot's
 * `KafkaAnnotationDrivenConfiguration` injects any `RecordInterceptor` bean into the listener
 * container factory it builds, and no service here declares a factory of its own.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate::class, RecordInterceptor::class)
class RequestIdKafkaAutoConfiguration {

    /**
     * Attaches the producer interceptor to every `KafkaTemplate` bean.
     *
     * A `BeanPostProcessor` rather than the `spring.kafka.producer.properties.interceptor.classes`
     * property because that route makes Kafka instantiate the class reflectively per producer,
     * while this keeps one instance and touches no service's configuration.
     */
    @Bean
    fun relayKafkaTemplateRequestIdPostProcessor(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (bean is KafkaTemplate<*, *>) {
                @Suppress("UNCHECKED_CAST")
                (bean as KafkaTemplate<Any?, Any?>).setProducerInterceptor(RequestIdProducerInterceptor())
            }
            return bean
        }
    }

    /**
     * MUST be declared as `RecordInterceptor<Any, Any>`.
     *
     * Boot injects `ObjectProvider<RecordInterceptor<Object, Object>>`. A bean typed
     * `RecordInterceptor<String, String>` does not match Spring's generic resolution and is
     * **silently dropped** — no warning, no error, Kafka correlation simply never happens. These
     * services all use String key/value serializers, so that mistake would look entirely reasonable.
     */
    @Bean
    @ConditionalOnMissingBean(RecordInterceptor::class)
    fun relayRequestIdRecordInterceptor(): RecordInterceptor<Any, Any> = RequestIdRecordInterceptor()
}

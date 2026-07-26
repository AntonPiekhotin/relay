package com.relay.websocket.consumer

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * Events arrive as plain JSON strings and are decoded here rather than by a Kafka
 * `JsonDeserializer`, which keeps type headers and trusted-package configuration out of the
 * picture and lets a bad payload be handled explicitly.
 */
@Component
class EventCodec(
    private val jsonMapper: JsonMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Returns null for a payload that cannot be decoded. Skipping is deliberate: this is a live
     * push channel, so retrying a malformed event forever would stall the partition and block
     * every well-formed event behind it.
     */
    fun <T> decode(topic: String, raw: String, type: Class<T>): T? =
        try {
            jsonMapper.readValue(raw, type)
        } catch (ex: Exception) {
            logger.error("Skipping malformed event on {}: {}", topic, raw.take(512), ex)
            null
        }
}
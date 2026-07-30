package com.relay.notification.output.push

import com.relay.notification.model.DeviceToken
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Stand-in until FCM credentials exist: logs exactly what would be sent, so the whole pipeline
 * (Kafka -> token lookup -> per-device fan-out) is exercisable locally end to end.
 *
 * When the real FCM adapter lands, mark it `@Primary` (or profile-gate this one) — the
 * consumer depends only on the [PushSender] port.
 */
@Component
class LoggingPushSender : PushSender {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun send(token: DeviceToken, message: PushMessage) {
        logger.info(
            "PUSH (stub) -> user={} device={} platform={} | {}: {} | data={}",
            token.userId, token.deviceId, token.platform, message.title, message.body, message.data
        )
    }
}
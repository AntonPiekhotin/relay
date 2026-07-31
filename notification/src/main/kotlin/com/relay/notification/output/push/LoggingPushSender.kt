package com.relay.notification.output.push

import com.relay.notification.model.DeviceToken
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The default transport when `relay.push.fcm.enabled` is off: logs exactly what would be sent,
 * so the whole pipeline (Kafka -> token lookup -> per-device fan-out) is exercisable locally
 * without Firebase credentials. With the flag on, [FcmPushSender] is `@Primary` and this bean
 * simply stops being injected.
 */
@Component
class LoggingPushSender : PushSender {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun send(token: DeviceToken, message: PushMessage): PushResult {
        logger.info(
            "PUSH (stub) -> user={} device={} platform={} | {}: {} | data={}",
            token.userId, token.deviceId, token.platform, message.title, message.body, message.data
        )
        return PushResult.SENT
    }
}
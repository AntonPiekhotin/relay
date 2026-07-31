package com.relay.notification.output.push

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import com.relay.notification.model.DeviceToken
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * FCM HTTP v1 adapter. `@Primary` behind the `relay.push.fcm.enabled` flag, so flipping the
 * property is the whole switch from the logging stub.
 *
 * Sends both a `notification` block (the OS displays it with zero client code) and the `data`
 * map (a real client uses it to open the right dialog / update state silently). Blocking send
 * is fine: this runs on Kafka listener threads, not an event loop.
 *
 * [dryRun] asks FCM to validate credentials, token and payload WITHOUT delivering — the way to
 * smoke-test the whole adapter before any client app exists.
 */
@Component
@Primary
@ConditionalOnProperty("relay.push.fcm.enabled", havingValue = "true")
class FcmPushSender(
    private val firebaseMessaging: FirebaseMessaging,
    @Value("\${relay.push.fcm.dry-run:false}") private val dryRun: Boolean
) : PushSender {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun send(token: DeviceToken, message: PushMessage): PushResult {
        val fcmToken = token.fcmToken
        if (fcmToken.isNullOrBlank()) {
            // A device registered without a push token (permission declined) — nothing to do.
            return PushResult.TOKEN_DEAD
        }
        return try {
            val id = firebaseMessaging.send(fcmMessage(fcmToken, message), dryRun)
            logger.debug("FCM accepted push {} for device {} (dryRun={})", id, token.deviceId, dryRun)
            PushResult.SENT
        } catch (ex: FirebaseMessagingException) {
            classify(ex, token)
        } catch (ex: Exception) {
            logger.error("Unexpected FCM failure for device {}", token.deviceId, ex)
            PushResult.TRANSIENT_FAILURE
        }
    }

    private fun fcmMessage(fcmToken: String, message: PushMessage): Message =
        Message.builder()
            .setToken(fcmToken)
            .setNotification(
                Notification.builder()
                    .setTitle(message.title)
                    .setBody(message.body)
                    .build()
            )
            .putAllData(message.data)
            // Chat is latency-sensitive; HIGH lets Android wake the app from doze.
            .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
            .build()

    private fun classify(ex: FirebaseMessagingException, token: DeviceToken): PushResult =
        when (ex.messagingErrorCode) {
            // UNREGISTERED: app uninstalled or the token rotated. INVALID_ARGUMENT with a token
            // present means the token itself is garbage. Both are permanent for this row.
            MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT -> {
                logger.info(
                    "FCM declared token of device {} (user {}) dead: {}",
                    token.deviceId, token.userId, ex.messagingErrorCode
                )
                PushResult.TOKEN_DEAD
            }
            else -> {
                logger.warn(
                    "FCM push to device {} failed transiently: {} {}",
                    token.deviceId, ex.messagingErrorCode, ex.message
                )
                PushResult.TRANSIENT_FAILURE
            }
        }
}

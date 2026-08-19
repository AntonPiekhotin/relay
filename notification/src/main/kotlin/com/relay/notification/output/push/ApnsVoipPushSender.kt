package com.relay.notification.output.push

import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.DeliveryPriority
import com.eatthepath.pushy.apns.PushType
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification
import com.relay.notification.model.DeviceToken
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * APNs VoIP adapter (Pushy). `apns-push-type: voip` against the `.voip` topic is the one send that
 * raises PushKit — and thereby CallKit — on a locked iPhone; nothing FCM can emit does.
 *
 * The payload is the same data map the FCM path carries, under an empty `aps` (VoIP pushes have no
 * visible part — the client's PushKit handler reads the map and reports the call to CallKit). The
 * notification expires at the ring deadline: APNs discarding a late push is strictly better than
 * ringing a phone for a call the server already declared missed.
 *
 * Blocking `.get()` on Pushy's future is correct here — this runs on a Kafka listener's virtual
 * thread, exactly like `FirebaseMessaging.send`, and the ban is on reactive chains, not on waiting.
 */
@Component
@ConditionalOnProperty("relay.push.apns.enabled", havingValue = "true")
class ApnsVoipPushSender(
    private val apnsClient: ApnsClient,
    @Value("\${relay.push.apns.bundle-id}") private val bundleId: String,
    private val jsonMapper: JsonMapper
) : VoipPushSender {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun send(token: DeviceToken, message: PushMessage): PushResult {
        val voipToken = token.voipToken
        if (voipToken.isNullOrBlank()) {
            return PushResult.TOKEN_DEAD
        }
        return try {
            val notification = SimpleApnsPushNotification(
                voipToken,
                "$bundleId.voip",
                payloadOf(message),
                message.expiresAt,
                DeliveryPriority.IMMEDIATE,
                PushType.VOIP
            )
            val response = apnsClient.sendNotification(notification).get()
            if (response.isAccepted) {
                logger.debug("APNs accepted VoIP push for device {}", token.deviceId)
                PushResult.SENT
            } else {
                classify(response.rejectionReason.orElse(null), token)
            }
        } catch (ex: Exception) {
            logger.error("Unexpected APNs failure for device {}", token.deviceId, ex)
            PushResult.TRANSIENT_FAILURE
        }
    }

    private fun payloadOf(message: PushMessage): String =
        jsonMapper.writeValueAsString(mapOf("aps" to emptyMap<String, Any>()) + message.data)

    private fun classify(reason: String?, token: DeviceToken): PushResult = when (reason) {
        // The voip token, not the device, is what these condemn — the caller clears the column.
        "BadDeviceToken", "Unregistered", "ExpiredToken", "DeviceTokenNotForTopic" -> {
            logger.info(
                "APNs declared the voip token of device {} (user {}) dead: {}",
                token.deviceId, token.userId, reason
            )
            PushResult.TOKEN_DEAD
        }
        else -> {
            logger.warn("APNs VoIP push to device {} failed transiently: {}", token.deviceId, reason)
            PushResult.TRANSIENT_FAILURE
        }
    }
}

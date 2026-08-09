package com.relay.notification.input.event

import com.relay.common.event.KafkaTopics
import com.relay.common.event.NotificationRequestedEvent
import com.relay.notification.model.DeviceToken
import com.relay.notification.output.push.PushMessage
import com.relay.notification.output.push.PushResult
import com.relay.notification.output.push.PushSender
import com.relay.notification.service.DeviceTokenService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

private const val PREVIEW_LENGTH = 140

/**
 * Consumes push requests for users the gateway found offline and fans them out to every device
 * the recipient has registered.
 *
 * Shared consumer group: requests are work to be processed exactly once, so instances compete
 * for partitions. Events are keyed by recipient, so one user's pushes stay ordered.
 *
 * A recipient with no registered devices is normal, not an error — a fresh account, or a user
 * who declined notification permission. The message itself is safe in the database either way.
 */
@Component
class NotificationRequestConsumer(
    private val deviceTokenService: DeviceTokenService,
    private val pushSender: PushSender,
    private val jsonMapper: JsonMapper
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [KafkaTopics.NOTIFICATIONS],
        groupId = "notification-service",
        concurrency = "#{T(com.relay.common.event.KafkaTopics).PARTITIONS}"
    )
    fun onNotificationRequested(raw: String) {
        try {
            val request = parseRequest(raw)
            val message = render(request)
            val tokens = deviceTokenService.tokensOf(request.recipientId)
            if (tokens.isEmpty()) {
                logger.debug("User {} has no registered devices, dropping {} push", request.recipientId, request.kind)
                return
            }
            sendToDevices(tokens, message)
        } catch (ex: Exception) {
            logger.error("Failed to process notification request: {}", raw.take(512), ex)
        }
    }

    private fun parseRequest(raw: String): NotificationRequestedEvent {
        return jsonMapper.readValue(raw, NotificationRequestedEvent::class.java)
    }

    private fun sendToDevices(tokens: List<DeviceToken>, message: PushMessage) {
        tokens.forEach { token ->
            when (pushSender.send(token, message)) {
                PushResult.SENT -> Unit
                // Self-healing token store: FCM declared this registration permanently invalid
                // (app uninstalled, token rotated), so keeping the row would only make every
                // future message waste a doomed call on it.
                PushResult.TOKEN_DEAD -> deviceTokenService.unregister(token.userId, token.deviceId)
                // Dropping is deliberate: the message is safe in the database (Principle 1),
                // the recipient catches up on next open — no retry machinery here.
                PushResult.TRANSIENT_FAILURE ->
                    logger.warn("Push to device {} of user {} failed transiently, dropped", token.deviceId, token.userId)
            }
        }
    }

    /**
     * Turns a request into displayable content. Kinds are added here as features grow
     * (CONTACT_REQUEST, ...); an unknown kind throws and the request is skipped.
     *
     * No display names anywhere: this service knows ids, and resolving them would mean calling
     * user-service on the push path. The client already has the contact and renders the name.
     */
    private fun render(request: NotificationRequestedEvent): PushMessage = when (request.kind) {
        NotificationRequestedEvent.KIND_MESSAGE_NEW -> PushMessage(
            title = "New message",
            body = (request.payload["text"] as? String)?.take(PREVIEW_LENGTH) ?: "You have a new message",
            data = mapOf(
                "kind" to request.kind,
                "dialogId" to request.payload["dialogId"].toString(),
                "messageId" to request.payload["messageId"].toString(),
                "senderId" to request.payload["senderId"].toString()
            )
        )

        /*
         * Data only, so the client can raise its own incoming-call UI instead of the OS drawing a
         * banner the user has to tap. `ringExpiresAt` travels along because this push is worthless
         * once the server has stopped ringing — a call notification for a call that is already
         * missed must not put an answer button on screen.
         */
        NotificationRequestedEvent.KIND_INCOMING_CALL -> PushMessage(
            title = "Incoming call",
            body = "Incoming ${request.mediaOrDefault()} call",
            data = mapOf(
                "kind" to request.kind,
                "callId" to request.payload[NotificationRequestedEvent.KEY_CALL_ID].toString(),
                "callerId" to request.payload[NotificationRequestedEvent.KEY_CALLER_ID].toString(),
                "media" to request.mediaOrDefault(),
                "ringExpiresAt" to request.payload[NotificationRequestedEvent.KEY_RING_EXPIRES_AT].toString()
            ),
            dataOnly = true
        )

        /* After the fact, so an ordinary visible alert is exactly right. */
        NotificationRequestedEvent.KIND_MISSED_CALL -> PushMessage(
            title = "Missed call",
            body = "You missed a ${request.mediaOrDefault()} call",
            data = mapOf(
                "kind" to request.kind,
                "callId" to request.payload[NotificationRequestedEvent.KEY_CALL_ID].toString(),
                "callerId" to request.payload[NotificationRequestedEvent.KEY_CALLER_ID].toString(),
                "media" to request.mediaOrDefault()
            )
        )

        else -> throw IllegalArgumentException("Unknown notification kind '${request.kind}'")
    }

    private fun NotificationRequestedEvent.mediaOrDefault(): String =
        payload[NotificationRequestedEvent.KEY_MEDIA] as? String ?: "voice"
}
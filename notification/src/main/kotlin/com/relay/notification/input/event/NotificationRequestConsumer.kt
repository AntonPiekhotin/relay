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
 * Consumes push requests for users the gateway found offline (ARCHITECTURE.md §14.2, §16.1) and
 * fans them out to every device the recipient has registered.
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

    @KafkaListener(topics = [KafkaTopics.NOTIFICATIONS], groupId = "notification-service")
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
     * (MISSED_CALL, CONTACT_REQUEST, ...); an unknown kind throws and the request is skipped.
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
        else -> throw IllegalArgumentException("Unknown notification kind '${request.kind}'")
    }

}
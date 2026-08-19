package com.relay.notification.service

import com.relay.notification.model.DeviceToken
import com.relay.notification.model.DeviceTokenId
import com.relay.notification.model.dto.DeviceTokenResponse
import com.relay.notification.model.dto.RegisterDeviceTokenRequest
import com.relay.notification.repository.DeviceTokenRepository
import com.relay.notification.util.mapper.toResponse
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DeviceTokenService(
    private val deviceTokenRepository: DeviceTokenRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Upsert by (userId, deviceId): FCM rotates tokens, so a device re-registering is routine,
     * not an error. A null token in the request clears the stored one — that is how a client
     * revokes push for one channel (e.g. notification permission withdrawn) without logging out.
     */
    @Transactional
    fun register(userId: String, request: RegisterDeviceTokenRequest): DeviceTokenResponse {
        val existing = deviceTokenRepository.findById(DeviceTokenId(userId, request.deviceId)).orElse(null)
        val saved = existing?.apply {
            platform = request.platform
            fcmToken = request.fcmToken
            voipToken = request.voipToken
            updatedAt = Instant.now()
        }
            ?: deviceTokenRepository.save(
                DeviceToken(
                    userId = userId,
                    deviceId = request.deviceId,
                    platform = request.platform,
                    fcmToken = request.fcmToken,
                    voipToken = request.voipToken
                )
            )
        logger.debug("Registered device {} for user {}", saved.deviceId, userId)
        return saved.toResponse()
    }

    /** Called on logout; idempotent, deleting an unknown device is not an error. */
    @Transactional
    fun unregister(userId: String, deviceId: String) {
        deviceTokenRepository.deleteById(DeviceTokenId(userId, deviceId))
        logger.debug("Unregistered device {} for user {}", deviceId, userId)
    }

    /**
     * APNs declared the device's *voip* token dead. Clears that column and nothing else — the same
     * device's FCM token may be perfectly alive, so deleting the row (the FCM-dead response) would
     * cost the device every other push too.
     */
    @Transactional
    fun clearVoipToken(userId: String, deviceId: String) {
        deviceTokenRepository.findById(DeviceTokenId(userId, deviceId)).orElse(null)?.let {
            it.voipToken = null
            it.updatedAt = Instant.now()
            logger.info("Cleared the dead voip token of device {} for user {}", deviceId, userId)
        }
    }

    @Transactional(readOnly = true)
    fun tokensOf(userId: String): List<DeviceToken> = deviceTokenRepository.findAllByUserId(userId)
}
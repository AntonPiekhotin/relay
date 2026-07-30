package com.relay.notification.util.mapper

import com.relay.notification.model.DeviceToken
import com.relay.notification.model.dto.DeviceTokenResponse

fun DeviceToken.toResponse() = DeviceTokenResponse(
    deviceId = deviceId,
    platform = platform,
    fcmToken = fcmToken,
    voipToken = voipToken,
    updatedAt = updatedAt
)
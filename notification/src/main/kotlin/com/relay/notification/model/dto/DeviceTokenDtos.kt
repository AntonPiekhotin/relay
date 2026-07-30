package com.relay.notification.model.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Registration payload. No userId field on purpose — the owner is always the JWT's `sub`, so a
 * client cannot register (or overwrite) tokens for somebody else.
 */
data class RegisterDeviceTokenRequest(

    @field:NotBlank
    @field:Size(max = 128)
    val deviceId: String,

    @field:Pattern(regexp = "ios|android|web", message = "platform must be ios, android or web")
    val platform: String,

    @field:Size(max = 4096)
    val fcmToken: String? = null,

    @field:Size(max = 4096)
    val voipToken: String? = null
)

data class DeviceTokenResponse(
    val deviceId: String,
    val platform: String,
    val fcmToken: String?,
    val voipToken: String?,
    val updatedAt: Instant
)
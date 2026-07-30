package com.relay.notification.input.web

import com.relay.common.exception.RelayException
import com.relay.notification.model.dto.DeviceTokenResponse
import com.relay.notification.model.dto.RegisterDeviceTokenRequest
import com.relay.notification.service.DeviceTokenService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Client-facing, reached through the api-gateway (the `/api/v1/notification` route to
 * `lb://notification`). The owner of every token is the JWT's `sub` — validated here, not just
 * at the gateway, per ARCHITECTURE.md §8.3: the gateway must not be the only line of defence.
 */
@RestController
@RequestMapping(path = ["/api/v1/notification/device-tokens"])
class DeviceTokenController(
    private val deviceTokenService: DeviceTokenService
) {

    /** PUT because re-registering the same device is routine (FCM rotates tokens). */
    @PutMapping
    fun register(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: RegisterDeviceTokenRequest
    ): ResponseEntity<DeviceTokenResponse> =
        ResponseEntity.ok(deviceTokenService.register(jwt.ownerId(), request))

    /** Called on logout, so a signed-out device stops receiving pushes. */
    @DeleteMapping("/{deviceId}")
    fun unregister(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable deviceId: String
    ): ResponseEntity<Void> {
        deviceTokenService.unregister(jwt.ownerId(), deviceId)
        return ResponseEntity.noContent().build()
    }

    /** Without a `sub` there is no user to own the token — refuse rather than guess. */
    private fun Jwt.ownerId(): String =
        subject ?: throw RelayException(HttpStatus.UNAUTHORIZED.value(), "Token carries no subject")
}
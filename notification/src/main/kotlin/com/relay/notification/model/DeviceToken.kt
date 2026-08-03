package com.relay.notification.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

data class DeviceTokenId(
    val userId: String = "",
    val deviceId: String = ""
) : Serializable

/**
 * One row per (user, device): a user carries several devices, and each re-registers its token
 * in place — hence the composite key rather than a surrogate id.
 *
 * [fcmToken] and [voipToken] are deliberately separate columns: on iOS, PushKit
 * VoIP tokens come from a different mechanism than APNs tokens and are not interchangeable.
 * Merging them would force a migration the moment calls ship.
 */
@Entity
@Table(name = "device_tokens")
@IdClass(DeviceTokenId::class)
class DeviceToken(

    @Id
    @Column(name = "user_id", nullable = false, updatable = false, length = 64)
    val userId: String,

    @Id
    @Column(name = "device_id", nullable = false, updatable = false, length = 128)
    val deviceId: String,

    @Column(name = "platform", nullable = false, length = 16)
    var platform: String,

    @Column(name = "fcm_token", columnDefinition = "text")
    var fcmToken: String?,

    @Column(name = "voip_token", columnDefinition = "text")
    var voipToken: String?,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
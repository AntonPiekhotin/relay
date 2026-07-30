package com.relay.notification.repository

import com.relay.notification.model.DeviceToken
import com.relay.notification.model.DeviceTokenId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DeviceTokenRepository : JpaRepository<DeviceToken, DeviceTokenId> {

    fun findAllByUserId(userId: String): List<DeviceToken>
}
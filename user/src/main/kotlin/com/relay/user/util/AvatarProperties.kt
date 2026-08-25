package com.relay.user.util

import com.relay.user.model.AVATAR_COLUMN_BYTES
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

@ConfigurationProperties(prefix = "relay.user.avatar")
data class AvatarProperties(
    val maxSize: DataSize = DataSize.ofMegabytes(1),
    val allowedContentTypes: Set<String> = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
) {
    init {
        require(maxSize.toBytes() in 1..AVATAR_COLUMN_BYTES) {
            "relay.user.avatar.max-size must be between 1B and ${AVATAR_COLUMN_BYTES}B, was $maxSize"
        }
        require(allowedContentTypes.isNotEmpty()) {
            "relay.user.avatar.allowed-content-types is empty, so no upload could ever succeed"
        }
    }
}
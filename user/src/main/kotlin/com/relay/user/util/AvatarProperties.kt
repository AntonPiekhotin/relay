package com.relay.user.util

import com.relay.user.model.AVATAR_COLUMN_BYTES
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

/**
 * [maxSize] must stay at or below the declared width of the `bytes` column, and below
 * `spring.servlet.multipart.max-file-size`; the [require] turns a misconfiguration into a startup
 * failure instead of a truncated write or a confusing 500 on the first upload.
 *
 * [allowedContentTypes] is matched against the type detected from the file's own bytes, never
 * against the `Content-Type` the client declared.
 */
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
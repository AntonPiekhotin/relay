package com.relay.user.service

import com.relay.common.exception.RelayException
import com.relay.user.mapper.toResponse
import com.relay.user.model.User
import com.relay.user.model.UserAvatar
import com.relay.user.model.dto.AvatarResponse
import com.relay.user.repository.UserAvatarRepository
import com.relay.user.repository.UserRepository
import com.relay.user.util.AvatarProperties
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

/**
 * Profile pictures. Two rules carry the security of this endpoint:
 *
 *  1. **The content type is read from the bytes, never from the request.** `Content-Type` on a
 *     multipart part is chosen by the client, and we hand these bytes back to browsers later — a
 *     file declared `image/png` but containing SVG or HTML would be stored and then served as a
 *     script-bearing document from our own origin. Sniffing means the type we store is the type the
 *     file actually is, and anything we cannot identify is refused.
 *  2. **Size is checked before the bytes are read**, so an oversized upload is rejected without
 *     being pulled into the heap.
 */
@Service
class AvatarService(
    private val userRepository: UserRepository,
    private val avatarRepository: UserAvatarRepository,
    private val props: AvatarProperties
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun upload(userId: String, file: MultipartFile): AvatarResponse {
        val user = requireUser(userId)
        if (file.isEmpty) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "Avatar file is empty")
        }
        if (file.size > props.maxSize.toBytes()) {
            throw RelayException(
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "Avatar must be at most ${props.maxSize.toKilobytes()} KB, got ${file.size / 1024} KB"
            )
        }
        val bytes = file.bytes
        val contentType = detectImageType(bytes)
            ?: throw unsupportedMedia("the file is not a recognisable image")
        if (contentType !in props.allowedContentTypes) {
            throw unsupportedMedia("$contentType is not accepted")
        }

        val now = Instant.now()
        val avatar = avatarRepository.findById(userId).orElse(null)?.apply {
            this.contentType = contentType
            this.bytes = bytes
            this.sizeBytes = bytes.size
            this.updatedAt = now
        } ?: avatarRepository.save(
            UserAvatar(
                userId = userId,
                contentType = contentType,
                bytes = bytes,
                sizeBytes = bytes.size,
                updatedAt = now
            )
        )
        user.avatarUrl = avatarUrlOf(userId, now)
        user.updatedAt = now
        logger.debug("Stored {} avatar of {} bytes for user {}", contentType, bytes.size, userId)
        return avatar.toResponse(user.avatarUrl!!)
    }

    /** Idempotent: clearing a picture nobody set is not an error. */
    @Transactional
    fun delete(userId: String) {
        val user = requireUser(userId)
        if (avatarRepository.existsById(userId)) {
            avatarRepository.deleteById(userId)
        }
        user.avatarUrl = null
        user.updatedAt = Instant.now()
        logger.debug("Cleared avatar for user {}", userId)
    }

    /** Any authenticated caller may load any user's picture — it is shown next to their messages. */
    @Transactional(readOnly = true)
    fun load(userId: String): UserAvatar =
        avatarRepository.findById(userId)
            .orElseThrow { RelayException(HttpStatus.NOT_FOUND.value(), "User $userId has no avatar") }

    private fun requireUser(id: String): User =
        userRepository.findById(id)
            .orElseThrow { RelayException(HttpStatus.NOT_FOUND.value(), "User $id not found") }

    private fun unsupportedMedia(reason: String) = RelayException(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
        "Unsupported avatar: $reason. Accepted: ${props.allowedContentTypes.sorted().joinToString()}"
    )

    companion object {

        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val GIF_MAGIC = "GIF8".toByteArray(Charsets.US_ASCII)
        private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WEBP_TAG = "WEBP".toByteArray(Charsets.US_ASCII)

        /**
         * The URL clients read from the profile. Relative on purpose — the host depends on which
         * edge the client came through (api-gateway, or nginx in front of it), and baking one in
         * would break the other. `v` is the version stamp, so a replaced picture is fetched again
         * instead of being served from a cache that cannot know it changed.
         */
        fun avatarUrlOf(userId: String, updatedAt: Instant): String =
            "/api/v1/user/$userId/avatar?v=${updatedAt.toEpochMilli()}"

        /** Returns the media type the bytes actually are, or null if it is not an image we accept. */
        fun detectImageType(bytes: ByteArray): String? = when {
            bytes.startsWith(PNG_MAGIC) -> "image/png"
            bytes.startsWith(JPEG_MAGIC) -> "image/jpeg"
            bytes.startsWith(GIF_MAGIC) -> "image/gif"
            bytes.isWebp() -> "image/webp"
            else -> null
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

        /** WebP is a RIFF container: `RIFF`, four bytes of length, then the `WEBP` form tag. */
        private fun ByteArray.isWebp(): Boolean =
            size >= 12 && startsWith(RIFF_MAGIC) && copyOfRange(8, 12).contentEquals(WEBP_TAG)
    }
}
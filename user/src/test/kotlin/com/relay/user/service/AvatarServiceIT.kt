package com.relay.user.service

import com.relay.common.dto.CreateUserRequest
import com.relay.common.exception.RelayException
import com.relay.user.repository.ContactRepository
import com.relay.user.repository.UserAvatarRepository
import com.relay.user.repository.UserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile

/**
 * Profile pictures. The load-bearing case is [`the stored type comes from the bytes`]: the media type
 * is decided by the file's signature, not by the `Content-Type` the client chose, because these bytes
 * are served back to browsers from our own origin.
 *
 * `max-size` is turned down to 1 KB so the limit can be exercised without a megabyte fixture.
 */
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "relay.user.avatar.max-size=1KB",
        "spring.datasource.url=jdbc:h2:mem:avatarit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
    ]
)
class AvatarServiceIT {

    @Autowired private lateinit var avatarService: AvatarService
    @Autowired private lateinit var userService: UserService
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var contactRepository: ContactRepository
    @Autowired private lateinit var avatarRepository: UserAvatarRepository

    @BeforeTest
    fun resetDatabase() {
        contactRepository.deleteAll()
        avatarRepository.deleteAll()
        userRepository.deleteAll()
        userService.create(
            CreateUserRequest(id = "alice", email = "alice@relay.test", firstName = "Alice", lastName = "A")
        )
    }

    /**
     * Only the signature matters: nothing here decodes an image, so a valid header plus filler is a
     * faithful fixture and keeps binary blobs out of the repository.
     */
    private fun png(size: Int = 64) =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(size)

    private fun gif(size: Int = 64) = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(size)

    private fun webp(size: Int = 64) =
        "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) +
            "WEBP".toByteArray(Charsets.US_ASCII) + ByteArray(size)

    private fun upload(bytes: ByteArray, declaredType: String = "image/png", name: String = "a.png") =
        avatarService.upload("alice", MockMultipartFile("file", name, declaredType, bytes))

    private fun avatarUrl() = userRepository.findById("alice").orElseThrow().avatarUrl

    @Test
    fun `stores a picture and publishes a versioned url on the profile`() {
        val response = upload(png())

        assertEquals("image/png", response.contentType)
        assertEquals(72, response.sizeBytes)
        assertEquals(response.avatarUrl, avatarUrl(), "the profile carries the same url as the response")
        assertTrue(
            response.avatarUrl.startsWith("/api/v1/user/alice/avatar?v="),
            "relative, so it works behind the gateway and behind nginx: ${response.avatarUrl}"
        )
        assertEquals("image/png", avatarService.load("alice").contentType)
    }

    @Test
    fun `the stored type comes from the bytes, not from the declared content type`() {
        val response = upload(gif(), declaredType = "image/png", name = "trust-me.png")

        assertEquals(
            "image/gif",
            response.contentType,
            "the client's Content-Type is a claim; the signature is evidence"
        )
        assertEquals("image/gif", avatarService.load("alice").contentType)
    }

    @Test
    fun `accepts every configured format`() {
        assertEquals("image/png", upload(png()).contentType)
        assertEquals("image/gif", upload(gif()).contentType)
        assertEquals("image/webp", upload(webp()).contentType)
    }

    @Test
    fun `refuses a file that is not an image, whatever it claims to be`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>"""

        val ex = assertFailsWith<RelayException> {
            upload(svg.toByteArray(), declaredType = "image/png", name = "xss.png")
        }

        assertEquals(415, ex.statusCode, "serving this back as image/png would be stored XSS")
        assertNull(avatarUrl())
    }

    @Test
    fun `refuses an empty file`() {
        val ex = assertFailsWith<RelayException> { upload(ByteArray(0)) }

        assertEquals(400, ex.statusCode)
    }

    @Test
    fun `refuses a file over the configured maximum`() {
        val ex = assertFailsWith<RelayException> { upload(png(size = 2048)) }

        assertEquals(413, ex.statusCode)
        assertNull(avatarUrl(), "a rejected upload leaves no trace on the profile")
    }

    @Test
    fun `replacing a picture reuses the row and moves the version forward`() {
        val first = upload(png(size = 16))
        val second = upload(gif(size = 32))

        assertEquals(1, avatarRepository.count(), "one row per user, not one per upload")
        assertEquals("image/gif", second.contentType)
        assertTrue(
            second.updatedAt >= first.updatedAt && second.avatarUrl == avatarUrl(),
            "a stale cache would keep showing the old face without a new version stamp"
        )
    }

    @Test
    fun `deleting clears both the bytes and the profile url`() {
        upload(png())

        avatarService.delete("alice")

        assertNull(avatarUrl())
        assertEquals(0, avatarRepository.count())
        assertEquals(404, assertFailsWith<RelayException> { avatarService.load("alice") }.statusCode)
    }

    @Test
    fun `deleting a picture that was never set is not an error`() {
        avatarService.delete("alice")

        assertNull(avatarUrl())
    }

    @Test
    fun `uploading for an unknown user is a 404`() {
        val ex = assertFailsWith<RelayException> {
            avatarService.upload("nobody", MockMultipartFile("file", "a.png", "image/png", png()))
        }

        assertEquals(404, ex.statusCode)
    }
}

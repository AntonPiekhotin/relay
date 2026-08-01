package com.relay.user.service

import com.relay.common.dto.CreateUserRequest
import com.relay.common.exception.RelayException
import com.relay.user.UserServiceIntegrationTest
import com.relay.user.model.dto.AddContactRequest
import com.relay.user.model.dto.UpdateProfileRequest
import com.relay.user.repository.ContactRepository
import com.relay.user.repository.UserAvatarRepository
import com.relay.user.repository.UserRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired

/**
 * Profile editing and user search. The search cases are the interesting ones: they pin the two rules
 * the endpoint's safety rests on — email matches only in full (so the endpoint cannot be walked to
 * enumerate addresses) and `LIKE` metacharacters in a query are literal text.
 */
@UserServiceIntegrationTest
class ProfileAndSearchIT {

    @Autowired private lateinit var userService: UserService
    @Autowired private lateinit var contactService: ContactService
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var contactRepository: ContactRepository
    @Autowired private lateinit var avatarRepository: UserAvatarRepository

    @BeforeTest
    fun resetDatabase() {
        contactRepository.deleteAll()
        avatarRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun user(id: String, email: String, first: String, last: String) =
        userService.create(CreateUserRequest(id = id, email = email, firstName = first, lastName = last))

    private fun alice() = user("alice", "alice@relay.test", "Alice", "Anderson")
    private fun bob() = user("bob", "bob@relay.test", "Bob", "Brown")

    // ---- profile ----

    @Test
    fun `replaces both names and touches nothing else`() {
        alice()

        val updated = userService.updateProfile("alice", UpdateProfileRequest("Alicia", "Zhu"))

        assertEquals("Alicia", updated.firstName)
        assertEquals("Zhu", updated.lastName)
        assertEquals("alice@relay.test", updated.email, "email is not part of the editable projection")
        assertEquals("alice", updated.id)
    }

    /**
     * The response is mapped from the managed entity, so it would look right even if nothing were
     * written. Re-reading in a fresh transaction is what actually proves dirty checking flushed —
     * there is no `save` call in [UserService.updateProfile] to eyeball.
     */
    @Test
    fun `the update is flushed without an explicit save`() {
        alice()

        userService.updateProfile("alice", UpdateProfileRequest("Alicia", "Zhu"))

        val reread = userRepository.findById("alice").orElseThrow()
        assertEquals("Alicia", reread.firstName)
        assertEquals("Zhu", reread.lastName)
        assertTrue(reread.updatedAt > reread.createdAt, "updatedAt moved with the change")
    }

    @Test
    fun `names are trimmed`() {
        alice()

        userService.updateProfile("alice", UpdateProfileRequest("  Alicia  ", "  Zhu "))

        val reread = userRepository.findById("alice").orElseThrow()
        assertEquals("Alicia", reread.firstName, "@NotBlank rejects values, it cannot trim them")
        assertEquals("Zhu", reread.lastName)
    }

    @Test
    fun `editing an unknown profile is a 404`() {
        val ex = assertFailsWith<RelayException> {
            userService.updateProfile("nobody", UpdateProfileRequest("Ghost", "Rider"))
        }

        assertEquals(404, ex.statusCode)
    }

    // ---- search ----

    @Test
    fun `finds a user by their full email`() {
        alice()
        bob()

        val found = userService.search("alice", "BOB@RELAY.TEST", page = 0, size = 20)

        assertEquals(listOf("bob"), found.items.map { it.user.id }, "email matching is case-insensitive")
    }

    @Test
    fun `does not find a user by an email prefix`() {
        alice()
        bob()

        val found = userService.search("alice", "bob@", page = 0, size = 20)

        assertTrue(found.items.isEmpty(), "a prefix match on email would let anyone enumerate addresses")
    }

    @Test
    fun `finds a user by a first or last name prefix`() {
        alice()
        bob()

        assertEquals(listOf("bob"), userService.search("alice", "bo", 0, 20).items.map { it.user.id })
        assertEquals(listOf("bob"), userService.search("alice", "BROW", 0, 20).items.map { it.user.id })
    }

    @Test
    fun `never returns the caller`() {
        alice()

        val found = userService.search("alice", "Alice", page = 0, size = 20)

        assertTrue(found.items.isEmpty(), "you cannot add yourself, so you are not a search result")
    }

    @Test
    fun `wildcards in the query are matched literally`() {
        alice()
        bob()

        val found = userService.search("alice", "%%", page = 0, size = 20)

        assertTrue(found.items.isEmpty(), "unescaped, '%%' would expand to LIKE '%%%' and return everyone")
    }

    @Test
    fun `refuses a query that is too short to be a search`() {
        val ex = assertFailsWith<RelayException> { userService.search("alice", "a", 0, 20) }

        assertEquals(400, ex.statusCode)
    }

    @Test
    fun `marks results that are already contacts`() {
        alice()
        bob()
        user("carol", "carol@relay.test", "Carol", "Brooks")
        contactService.add("alice", AddContactRequest("bob"))

        val found = userService.search("alice", "br", page = 0, size = 20)

        assertEquals(listOf("bob", "carol"), found.items.map { it.user.id }, "sorted by first name")
        assertEquals(
            mapOf("bob" to true, "carol" to false),
            found.items.associate { it.user.id to it.contact },
            "the flag saves the client a round trip per row to render Add vs Added"
        )
    }

    @Test
    fun `pages deterministically`() {
        alice()
        bob()
        user("carol", "carol@relay.test", "Carol", "Brown")

        val first = userService.search("alice", "brown", page = 0, size = 1)
        val second = userService.search("alice", "brown", page = 1, size = 1)

        assertEquals(2, first.totalElements)
        assertTrue(first.hasNext)
        assertEquals(listOf("bob"), first.items.map { it.user.id })
        assertEquals(listOf("carol"), second.items.map { it.user.id })
        assertTrue(!second.hasNext)
    }

    @Test
    fun `clamps an unreasonable page size instead of rejecting it`() {
        alice()
        bob()

        val found = userService.search("alice", "brown", page = -5, size = 10_000)

        assertEquals(0, found.page)
        assertEquals(100, found.size)
    }
}

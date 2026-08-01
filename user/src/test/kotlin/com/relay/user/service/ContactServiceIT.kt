package com.relay.user.service

import com.relay.common.dto.CreateUserRequest
import com.relay.common.exception.RelayException
import com.relay.user.UserServiceIntegrationTest
import com.relay.user.model.dto.AddContactRequest
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
 * Contacts as a one-sided address book. The invariants worth pinning are the ones a client will hit
 * by accident: adding twice (a retry), removing twice, and the fact that being added does not add
 * you back — every one of which would otherwise show up as a spurious error or a phantom contact.
 */
@UserServiceIntegrationTest
class ContactServiceIT {

    @Autowired private lateinit var contactService: ContactService
    @Autowired private lateinit var userService: UserService
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var contactRepository: ContactRepository
    @Autowired private lateinit var avatarRepository: UserAvatarRepository

    @BeforeTest
    fun resetDatabase() {
        contactRepository.deleteAll()
        avatarRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun user(id: String, first: String, last: String = "Tester") =
        userService.create(
            CreateUserRequest(id = id, email = "$id@relay.test", firstName = first, lastName = last)
        )

    private fun add(owner: String, contact: String) =
        contactService.add(owner, AddContactRequest(contact))

    @Test
    fun `adds a contact and reports it as newly created`() {
        user("alice", "Alice")
        user("bob", "Bob")

        val result = add("alice", "bob")

        assertTrue(result.created)
        assertEquals("bob", result.contact.user.id)
        assertEquals("bob@relay.test", result.contact.user.email)
        assertEquals(1, contactService.count("alice"))
    }

    @Test
    fun `adding the same contact again is not an error`() {
        user("alice", "Alice")
        user("bob", "Bob")

        val first = add("alice", "bob")
        val second = add("alice", "bob")

        assertTrue(first.created)
        assertTrue(!second.created, "a retried add is answered, not rejected")
        assertEquals(first.contact.addedAt, second.contact.addedAt, "the original row is kept")
        assertEquals(1, contactService.count("alice"))
    }

    @Test
    fun `adding is one-sided`() {
        user("alice", "Alice")
        user("bob", "Bob")

        add("alice", "bob")

        assertTrue(contactService.isContact("alice", "bob"))
        assertTrue(!contactService.isContact("bob", "alice"), "B did not ask for A in their list")
        assertEquals(0, contactService.count("bob"))
    }

    @Test
    fun `refuses to add yourself`() {
        user("alice", "Alice")

        val ex = assertFailsWith<RelayException> { add("alice", "alice") }

        assertEquals(400, ex.statusCode)
    }

    @Test
    fun `refuses an id that is not a user`() {
        user("alice", "Alice")

        val ex = assertFailsWith<RelayException> { add("alice", "nobody") }

        assertEquals(404, ex.statusCode, "there is no foreign key, so the service has to check")
        assertEquals(0, contactService.count("alice"))
    }

    @Test
    fun `removing is idempotent`() {
        user("alice", "Alice")
        user("bob", "Bob")
        add("alice", "bob")

        contactService.remove("alice", "bob")
        contactService.remove("alice", "bob")
        contactService.remove("alice", "never-was-a-contact")

        assertEquals(0, contactService.count("alice"))
    }

    @Test
    fun `removing one contact leaves the others`() {
        user("alice", "Alice")
        user("bob", "Bob")
        user("carol", "Carol")
        add("alice", "bob")
        add("alice", "carol")

        contactService.remove("alice", "bob")

        assertEquals(listOf("carol"), contactService.list("alice", 0, 20).items.map { it.user.id })
    }

    @Test
    fun `lists contacts by name and pages them`() {
        user("alice", "Alice")
        user("zoe", "Zoe")
        user("bob", "Bob")
        user("carol", "Carol")
        add("alice", "zoe")
        add("alice", "bob")
        add("alice", "carol")

        val first = contactService.list("alice", page = 0, size = 2)
        val second = contactService.list("alice", page = 1, size = 2)

        assertEquals(3, first.totalElements)
        assertEquals(2, first.totalPages)
        assertTrue(first.hasNext)
        assertEquals(
            listOf("bob", "carol"),
            first.items.map { it.user.id },
            "an address book is read alphabetically, not by insertion order"
        )
        assertEquals(listOf("zoe"), second.items.map { it.user.id })
        assertTrue(!second.hasNext)
    }

    @Test
    fun `an empty contact list does not run a query with an empty IN clause`() {
        user("alice", "Alice")

        val contacts = contactService.list("alice", page = 0, size = 20)

        assertTrue(contacts.items.isEmpty())
        assertEquals(0, contacts.totalElements)
    }

    @Test
    fun `each contact carries when it was added`() {
        user("alice", "Alice")
        user("bob", "Bob")

        val added = add("alice", "bob").contact.addedAt
        val listed = contactService.list("alice", 0, 20).items.single()

        assertEquals(added, listed.addedAt, "addedAt comes from the contacts row, not the profile")
    }
}

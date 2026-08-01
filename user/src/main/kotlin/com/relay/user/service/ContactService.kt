package com.relay.user.service

import com.relay.common.exception.RelayException
import com.relay.user.mapper.toContactResponse
import com.relay.user.mapper.toSummary
import com.relay.user.model.Contact
import com.relay.user.model.ContactId
import com.relay.user.model.User
import com.relay.user.model.dto.AddContactRequest
import com.relay.user.model.dto.AddContactResult
import com.relay.user.model.dto.ContactResponse
import com.relay.user.model.dto.PagedResponse
import com.relay.user.repository.ContactRepository
import com.relay.user.repository.UserRepository
import com.relay.user.util.pageRequestOf
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ContactService(
    private val contactRepository: ContactRepository,
    private val userRepository: UserRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Adding is one-sided (see [Contact]) and idempotent: a client that retries gets the same
     * contact back with `created = false` rather than an error, because a retry is not a mistake.
     *
     * The existence check on the target is what stops contacts pointing at ids that were never
     * users — the composite key gives us no foreign key to lean on.
     */
    @Transactional
    fun add(ownerId: String, request: AddContactRequest): AddContactResult {
        if (request.userId == ownerId) {
            throw RelayException(HttpStatus.BAD_REQUEST.value(), "You cannot add yourself as a contact")
        }
        val target = userRepository.findById(request.userId)
            .orElseThrow { RelayException(HttpStatus.NOT_FOUND.value(), "User ${request.userId} not found") }
        contactRepository.findById(ContactId(ownerId, request.userId)).orElse(null)?.let {
            return AddContactResult(target.toContactResponse(it), created = false)
        }
        return try {
            val saved = contactRepository.saveAndFlush(Contact(ownerId, request.userId))
            logger.debug("User {} added contact {}", ownerId, request.userId)
            AddContactResult(target.toContactResponse(saved), created = true)
        } catch (ex: DataIntegrityViolationException) {
            throw RelayException(
                HttpStatus.CONFLICT.value(),
                "User ${request.userId} is already in your contacts",
                ex
            )
        }
    }

    /** Idempotent, like unregistering a device token: removing somebody you never had is not an error. */
    @Transactional
    fun remove(ownerId: String, contactUserId: String) {
        val id = ContactId(ownerId, contactUserId)
        if (contactRepository.existsById(id)) {
            contactRepository.deleteById(id)
            logger.debug("User {} removed contact {}", ownerId, contactUserId)
        }
    }

    /**
     * Sorted like an address book (by name, not by when they were added) because that is how a
     * contact list is read. `addedAt` comes from a second query over just this page's ids — the
     * alternative, paging the `contacts` rows first, cannot sort by a column it does not have.
     */
    @Transactional(readOnly = true)
    fun list(ownerId: String, page: Int, size: Int): PagedResponse<ContactResponse> {
        val contacts = contactRepository.findContactsOf(ownerId, pageRequestOf(page, size))
        val addedAt = addedAtByContactUserId(ownerId, contacts.content.map(User::id))
        return PagedResponse.of(contacts) {
            ContactResponse(user = it.toSummary(), addedAt = addedAt[it.id] ?: it.createdAt)
        }
    }

    @Transactional(readOnly = true)
    fun isContact(ownerId: String, contactUserId: String): Boolean =
        contactRepository.existsByOwnerIdAndContactUserId(ownerId, contactUserId)

    @Transactional(readOnly = true)
    fun count(ownerId: String): Long = contactRepository.countByOwnerId(ownerId)

    /** Guarded against an empty list: a derived `in ()` query is a syntax error on most databases. */
    private fun addedAtByContactUserId(ownerId: String, userIds: List<String>): Map<String, Instant> =
        if (userIds.isEmpty()) emptyMap()
        else contactRepository.findAllByOwnerIdAndContactUserIdIn(ownerId, userIds)
            .associate { it.contactUserId to it.createdAt }
}
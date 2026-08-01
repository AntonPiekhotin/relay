package com.relay.user.repository

import com.relay.user.model.Contact
import com.relay.user.model.ContactId
import com.relay.user.model.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ContactRepository : JpaRepository<Contact, ContactId> {

    /**
     * The contacts of [ownerId] as profiles, sorted like an address book. Returning `User` from a
     * contact repository rather than joining in the service keeps this one query plus its count —
     * fetching `Contact` rows first and then their users would page by insertion order, so a page
     * could not be sorted by name at all.
     */
    @Query(
        value = """
            select u from User u
            where u.id in (select c.contactUserId from Contact c where c.ownerId = :ownerId)
            order by lower(u.firstName), lower(u.lastName), u.id
        """,
        countQuery = """
            select count(u) from User u
            where u.id in (select c.contactUserId from Contact c where c.ownerId = :ownerId)
        """
    )
    fun findContactsOf(@Param("ownerId") ownerId: String, pageable: Pageable): Page<User>

    fun findAllByOwnerIdAndContactUserIdIn(ownerId: String, contactUserIds: Collection<String>): List<Contact>

    fun existsByOwnerIdAndContactUserId(ownerId: String, contactUserId: String): Boolean

    fun countByOwnerId(ownerId: String): Long
}
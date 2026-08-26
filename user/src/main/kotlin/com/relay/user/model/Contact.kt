package com.relay.user.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit

data class ContactId(
    val ownerId: String = "",
    val contactUserId: String = ""
) : Serializable

/**
 * A directed edge: [ownerId] keeps [contactUserId] in their list. Adding is one-sided and needs
 * no consent, like a phone's address book — B is not added back to A's list and is not asked to
 * accept. A request/accept state machine belongs with blocking, and neither exists yet.
 *
 * Composite key rather than a surrogate id, so "already in my contacts" is a primary-key hit and
 * a double-add cannot create a second row. The reverse index answers "who has me" — needed the
 * moment we notify a user that somebody added them.
 */
@Entity
@Table(
    name = "contacts",
    indexes = [Index(name = "idx_contacts_contact_user", columnList = "contact_user_id")]
)
@IdClass(ContactId::class)
class Contact(

    @Id
    @Column(name = "owner_id", nullable = false, updatable = false, length = 64)
    val ownerId: String,

    @Id
    @Column(name = "contact_user_id", nullable = false, updatable = false, length = 64)
    val contactUserId: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
)
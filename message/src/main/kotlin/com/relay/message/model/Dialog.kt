package com.relay.message.model

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Membership lives here so message-service can resolve who receives a message. That resolution
 * has to be server-side: the fan-out list ends up on `messages.delivery` and drives what the
 * gateway pushes, so letting a client name its own recipients would let it push to anyone.
 *
 * Participants are eager because every send needs them.
 */
@Entity
@Table(name = "dialogs")
class Dialog(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "dialog_participants", joinColumns = [JoinColumn(name = "dialog_id")])
    @Column(name = "user_id", nullable = false, length = 64)
    val participantIds: MutableSet<String> = mutableSetOf(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)